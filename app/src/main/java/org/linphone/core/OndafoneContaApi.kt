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
import androidx.annotation.WorkerThread
import org.linphone.BuildConfig
import org.linphone.utils.OndafoneConta

/** Conta do app no givm (#390). Bloqueante: chamar fora da UI. */
interface OndafoneContaApi {
    val temSessao: Boolean

    /** E-mail da conta deste aparelho (só para exibir). */
    val email: String?

    /** Pede o código por e-mail. A resposta do givm é sempre a mesma (não diz se o e-mail existe). */
    @WorkerThread fun pedirCodigo(email: String, aceite: Boolean, marketing: Boolean): Resultado<Unit>

    /** Confere o código e grava a sessão do aparelho. */
    @WorkerThread fun confirmar(email: String, codigo: String): Resultado<Unit>

    /** Entra (ou cria a conta, com o aceite) com o ID token do Google; o e-mail vem do token, no givm. */
    @WorkerThread fun entrarComGoogle(idToken: String, email: String?, aceite: Boolean, marketing: Boolean): Resultado<Unit>

    /** Sai deste aparelho; a sessão local some mesmo sem rede. */
    @WorkerThread fun sair(): Resultado<Unit>

    /** Exclusão definitiva da conta (Google Play / LGPD); a sessão local some quando o givm confirma. */
    @WorkerThread fun excluir(): Resultado<Unit>

    companion object {
        fun criar(context: Context): OndafoneContaApi =
            if (BuildConfig.ONDAFONE_CONTA_MOCK) OndafoneContaMock else OndafoneContaGivm(context.applicationContext)
    }
}

class OndafoneContaGivm(private val context: Context) : OndafoneContaApi {
    override val temSessao: Boolean get() = OndafoneSessao.temSessao(context)
    override val email: String? get() = OndafoneSessao.email(context)

    private fun falha(r: OndafoneSessao.Resposta) = Resultado.Falha(OndafoneSessao.lerErro(r.status, r.corpo))

    private fun gravar(r: OndafoneSessao.Resposta, email: String?): Resultado<Unit> {
        if (!r.ok) return falha(r)
        return if (r.corpo != null && OndafoneSessao.salvarSessao(context, r.corpo)) {
            OndafoneSessao.salvarEmail(context, OndafoneConta.emailDaResposta(r.corpo) ?: email)
            Resultado.Ok(Unit)
        } else {
            Resultado.Falha(Erro(r.status, "resposta", null))
        }
    }

    override fun pedirCodigo(email: String, aceite: Boolean, marketing: Boolean): Resultado<Unit> {
        val r = OndafoneSessao.semSessao("POST", "/ondafone/cadastro", OndafoneConta.corpoCadastro(email, aceite, marketing))
        return if (r.ok) Resultado.Ok(Unit) else falha(r)
    }

    override fun confirmar(email: String, codigo: String): Resultado<Unit> = gravar(
        OndafoneSessao.semSessao(
            "POST",
            "/ondafone/cadastro/confirmar",
            OndafoneConta.corpoConfirmar(email, codigo, BuildConfig.VERSION_NAME, OndafoneSessao.aparelhoId(context))
        ),
        OndafoneConta.normalizarEmail(email)
    )

    override fun entrarComGoogle(idToken: String, email: String?, aceite: Boolean, marketing: Boolean) = gravar(
        OndafoneSessao.semSessao(
            "POST",
            "/ondafone/login/google",
            OndafoneConta.corpoGoogle(
                idToken,
                aceite,
                marketing,
                BuildConfig.VERSION_NAME,
                OndafoneSessao.aparelhoId(context)
            )
        ),
        email?.let { OndafoneConta.normalizarEmail(it) }
    )

    override fun sair(): Resultado<Unit> {
        val r = OndafoneSessao.requisitar(context, "POST", "/ondafone/sessao/sair", OndafoneConta.corpoSair(false))
        OndafoneSessao.apagarSessao(context)
        return if (r.ok || r.status <= 0 || r.status == 401) Resultado.Ok(Unit) else falha(r)
    }

    override fun excluir(): Resultado<Unit> {
        val r = OndafoneSessao.requisitar(context, "POST", "/ondafone/conta/excluir")
        if (!r.ok) return falha(r)
        OndafoneSessao.apagarSessao(context)
        return Resultado.Ok(Unit)
    }
}

/** Mock do debug (até o givm publicar o #390): o código é sempre 123456; nada sai do aparelho. */
object OndafoneContaMock : OndafoneContaApi {
    const val CODIGO = "123456"
    const val EMAIL_GOOGLE = "conta.google@exemplo.com.br"

    @Volatile
    private var sessao = false

    @Volatile
    private var emailSessao: String? = null
    private var emailPedido: String? = null
    private var aceitePedido = false

    /** E-mails que já têm conta no mock (conta nova exige o aceite; conta existente não). */
    private val contas = mutableSetOf<String>()

    override val temSessao: Boolean get() = sessao
    override val email: String? get() = emailSessao

    @Synchronized
    override fun pedirCodigo(email: String, aceite: Boolean, marketing: Boolean): Resultado<Unit> {
        if (!OndafoneConta.emailValido(email)) return Resultado.Falha(Erro(422, "email", "Informe um e-mail válido."))
        // como o givm: o código sai sempre (não revela se o e-mail tem conta); o aceite é cobrado no confirmar
        emailPedido = OndafoneConta.normalizarEmail(email)
        aceitePedido = aceite
        return Resultado.Ok(Unit)
    }

    @Synchronized
    override fun confirmar(email: String, codigo: String): Resultado<Unit> {
        val e = OndafoneConta.normalizarEmail(email)
        if (emailPedido != e || OndafoneConta.normalizarCodigo(codigo) != CODIGO) {
            return Resultado.Falha(Erro(401, "codigo", "Código inválido ou vencido."))
        }
        if (e !in contas && !aceitePedido) {
            return Resultado.Falha(Erro(422, "termos", "Aceite os termos para criar a conta."))
        }
        contas += e
        emailSessao = e
        sessao = true
        return Resultado.Ok(Unit)
    }

    @Synchronized
    override fun entrarComGoogle(idToken: String, email: String?, aceite: Boolean, marketing: Boolean): Resultado<Unit> {
        if (idToken.length < 20) return Resultado.Falha(Erro(401, "google", "Não foi possível confirmar a conta Google."))
        val e = EMAIL_GOOGLE
        if (!aceite && e !in contas) return Resultado.Falha(Erro(422, "termos", "Aceite os termos para criar a conta."))
        contas += e
        emailSessao = e
        sessao = true
        return Resultado.Ok(Unit)
    }

    /** Só para os testes: volta ao estado inicial. */
    @Synchronized
    fun reiniciar() {
        contas.clear()
        sessao = false
        emailSessao = null
        emailPedido = null
        aceitePedido = false
    }

    @Synchronized
    override fun sair(): Resultado<Unit> {
        sessao = false
        emailSessao = null
        return Resultado.Ok(Unit)
    }

    @Synchronized
    override fun excluir(): Resultado<Unit> {
        emailSessao?.let { contas -= it }
        sessao = false
        emailSessao = null
        emailPedido = null
        return Resultado.Ok(Unit)
    }
}
