/*
 * Copyright (c) 2026 Grupo IVM (Nexus / Voipmundo).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.linphone.BuildConfig
import org.linphone.core.tools.Log
import org.linphone.utils.OndafoneConta

/** Resultado de uma chamada ao givm: o valor, ou o erro (status 0 = sem rede ou sem sessão). */
sealed class Resultado<out T> {
    data class Ok<T>(val valor: T) : Resultado<T>()
    data class Falha(val erro: Erro) : Resultado<Nothing>()
}

/** Erro do givm: `{"detail": {"motivo", "mensagem"}}`; a mensagem já vem pronta para o usuário. */
data class Erro(val status: Int, val motivo: String?, val mensagem: String?)

/**
 * Sessão do app no givm (/api/ondafone/…, #390: Bearer aud=ondafone, refresh com rotação). Os tokens ficam só no
 * EncryptedSharedPreferences, fora do backup, e nunca vão para o log.
 */
object OndafoneSessao {
    private const val TAG = "[Ondafone Sessao]"

    const val API = "https://api.givm.com.br/api"
    private const val ARQUIVO = "ondafone_sessao"
    private const val ACCESS = "access"
    private const val REFRESH = "refresh"
    private const val EXPIRA_MS = "expira_ms"
    private const val EMAIL = "email"
    private const val ARQUIVO_APARELHO = "ondafone_aparelho"
    private const val APARELHO_ID = "aparelho_id"

    /** Renova um pouco antes de vencer, para o access não vencer no meio da requisição. */
    private const val MARGEM_MS = 60_000L
    private const val TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 64 * 1024

    /** Status da [Resposta] quando não há (mais) sessão neste aparelho: o usuário precisa entrar de novo. */
    const val SEM_SESSAO = -1

    data class Resposta(val status: Int, val corpo: String?) {
        val ok: Boolean get() = status in 200..299
    }

    /** Sem access: a sessão sumiu (refresh recusado) ou só não deu para renovar agora (rede). */
    private fun semAcesso(context: Context): Resposta =
        if (temSessao(context)) falha("não renovou") else Resposta(SEM_SESSAO, null).also { Log.w("$TAG sem sessão") }

    /** status 0 = sem rede (ou não deu para renovar agora); o motivo vai só para o log. */
    private fun falha(motivo: String): Resposta {
        Log.w("$TAG $motivo")
        return Resposta(0, null)
    }

    private val rede = Executors.newSingleThreadExecutor { r -> Thread(r, "Ondafone givm") }
    private val principal = Handler(Looper.getMainLooper())
    private val trava = Any()

    /** Roda [trabalho] fora da UI e do thread do Core, e entrega o resultado na UI. */
    fun <T> emSegundoPlano(trabalho: () -> T, depois: (T) -> Unit) {
        rede.execute {
            val r = trabalho()
            principal.post { depois(r) }
        }
    }

    /** Criadas uma vez (MasterKey no Keystore + EncryptedSharedPreferences custam caro a cada leitura). */
    @Volatile
    private var prefsCache: SharedPreferences? = null

    private fun preferencias(context: Context): SharedPreferences? {
        prefsCache?.let { return it }
        return synchronized(trava) {
            prefsCache ?: try {
                val chave = MasterKey.Builder(context.applicationContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    ARQUIVO,
                    chave,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { prefsCache = it }
            } catch (e: Exception) {
                // Arquivo de outro aparelho (backup restaurado) ou Keystore indisponível: sem sessão, entra de novo.
                // Não guarda a falha: a próxima chamada tenta de novo com o arquivo já apagado.
                Log.e("$TAG Sessão ilegível (${e.javaClass.simpleName}), apagando")
                context.deleteSharedPreferences(ARQUIVO)
                null
            }
        }
    }

    fun temSessao(context: Context): Boolean =
        preferencias(context)?.getString(REFRESH, null) != null

    /** Grava o par devolvido por /ondafone/cadastro/confirmar, /login/google ou /sessao/renovar. */
    fun salvarSessao(context: Context, json: String): Boolean {
        val o = try { JSONObject(json) } catch (_: Exception) { return false }
        val access = o.optString("accessToken").ifBlank { null } ?: return false
        val refresh = o.optString("refreshToken").ifBlank { null } ?: return false
        val expira = isoParaMs(o.optString("expiraEm")) ?: return false
        val p = preferencias(context) ?: return false
        p.edit(commit = true) {
            putString(ACCESS, access)
            putString(REFRESH, refresh)
            putLong(EXPIRA_MS, expira)
        }
        return true
    }

    /**
     * Id desta instalação para o `dispositivo.aparelhoId` do givm: gerado uma vez e guardado em SharedPreferences
     * privadas fora do backup (reinstalar ou outro aparelho = outro id). Não é segredo nem dado pessoal.
     */
    fun aparelhoId(context: Context): String = synchronized(trava) {
        val p = context.getSharedPreferences(ARQUIVO_APARELHO, Context.MODE_PRIVATE)
        p.getString(APARELHO_ID, null)?.takeIf { OndafoneConta.aparelhoIdValido(it) }
            ?: OndafoneConta.novoAparelhoId().also { p.edit().putString(APARELHO_ID, it).apply() }
    }

    /** E-mail da conta, só para mostrar na tela da conta (fica junto da sessão e sai com ela). */
    fun email(context: Context): String? = preferencias(context)?.getString(EMAIL, null)

    fun salvarEmail(context: Context, email: String?) {
        preferencias(context)?.edit(commit = true) { if (email == null) remove(EMAIL) else putString(EMAIL, email) }
    }

    fun lerErro(status: Int, json: String?): Erro = if (status == SEM_SESSAO) Erro(status, "sessao", null) else try {
        val d = JSONObject(json ?: "").optJSONObject("detail")
        fun t(k: String) = if (d == null || d.isNull(k)) null else d.optString(k).ifBlank { null }
        Erro(status, t("motivo"), t("mensagem"))
    } catch (_: Exception) {
        Erro(status, null, null)
    }

    fun apagarSessao(context: Context) {
        preferencias(context)?.edit(commit = true) { clear() }
    }

    fun isoParaMs(iso: String?): Long? = try {
        if (iso.isNullOrBlank()) null else OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    /**
     * Requisição autenticada. Renova antes se o access estiver vencendo, e uma vez depois de um 401. Refresh recusado
     * (401) apaga a sessão: o #390 revoga a sessão inteira quando um refresh é reusado.
     */
    @WorkerThread
    fun requisitar(
        context: Context,
        metodo: String,
        caminho: String,
        corpo: String? = null,
        idempotencia: String? = null
    ): Resposta {
        var access = accessValido(context, forcar = false) ?: return semAcesso(context)
        val r = http(metodo, caminho, corpo, access, idempotencia)
        if (r.status != 401) return r
        access = accessValido(context, forcar = true)
            ?: return if (temSessao(context)) r else Resposta(SEM_SESSAO, null)
        return http(metodo, caminho, corpo, access, idempotencia)
    }

    /** Requisição sem sessão (cadastro, código, exclusão pela web). */
    @WorkerThread
    fun semSessao(metodo: String, caminho: String, corpo: String?): Resposta = http(metodo, caminho, corpo, null, null)

    @WorkerThread
    private fun accessValido(context: Context, forcar: Boolean): String? = synchronized(trava) {
        val p = preferencias(context) ?: return null
        val access = p.getString(ACCESS, null)
        val refresh = p.getString(REFRESH, null) ?: return null
        if (!forcar && access != null && System.currentTimeMillis() < p.getLong(EXPIRA_MS, 0) - MARGEM_MS) {
            return access
        }
        val corpo = JSONObject()
            .put("refreshToken", refresh)
            .put("dispositivo", OndafoneConta.dispositivo(BuildConfig.VERSION_NAME, aparelhoId(context)))
            .toString()
        val r = http("POST", "/ondafone/sessao/renovar", corpo, null, null)
        when {
            r.ok && r.corpo != null && salvarSessao(context, r.corpo) -> p.getString(ACCESS, null)
            r.status == 401 || r.status == 403 -> {
                Log.w("$TAG Refresh recusado (HTTP ${r.status}), sessão apagada")
                apagarSessao(context)
                null
            }
            else -> {
                Log.w("$TAG Não renovou (HTTP ${r.status})")
                null
            }
        }
    }

    @WorkerThread
    private fun http(metodo: String, caminho: String, corpo: String?, access: String?, idempotencia: String?): Resposta {
        val conexao = try {
            URL(API + caminho).openConnection() as? HttpsURLConnection
        } catch (e: IOException) {
            return falha("$metodo $caminho: ${e.javaClass.simpleName}")
        } ?: return falha("$metodo $caminho: não é https")
        try {
            conexao.requestMethod = metodo
            conexao.instanceFollowRedirects = false
            conexao.useCaches = false
            conexao.connectTimeout = TIMEOUT_MS
            conexao.readTimeout = TIMEOUT_MS
            conexao.setRequestProperty("Accept", "application/json")
            if (access != null) conexao.setRequestProperty("Authorization", "Bearer $access")
            if (idempotencia != null) conexao.setRequestProperty("Idempotency-Key", idempotencia)
            if (corpo != null) {
                val bytes = corpo.toByteArray(Charsets.UTF_8)
                conexao.doOutput = true
                conexao.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conexao.setFixedLengthStreamingMode(bytes.size)
                conexao.outputStream.use { it.write(bytes) }
            }
            val status = conexao.responseCode
            Log.i("$TAG $metodo $caminho -> HTTP $status")
            val stream = if (status >= 400) conexao.errorStream else conexao.inputStream
            return Resposta(status, stream?.use { ler(it) })
        } catch (e: IOException) {
            return falha("$metodo $caminho: ${e.javaClass.simpleName}")
        } finally {
            conexao.disconnect()
        }
    }

    private fun ler(input: java.io.InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
