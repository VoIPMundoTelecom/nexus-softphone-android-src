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
package org.linphone.utils

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lista ASSINADA dos proxies "nossos" (givm, `GET /api/ondafone/proxies`), regras puras para teste de unidade.
 *
 * Envelope: `{"payload_b64": "<base64 do JSON>", "sig_b64": "<base64 da assinatura Ed25519 sobre os bytes do
 * payload>"}`. Payload: `{"versao": <inteiro>, "validoAte": "<ISO-8601 UTC>", "kid": "<16 hex>", "hosts": [...],
 * "dominiosTls": [...]}`. A assinatura cobre os bytes exatos do payload (sem JSON canônico a reproduzir aqui).
 *
 * Ordem: 1) Ed25519 sobre os BYTES decodificados de `payload_b64` com as chaves embutidas ([CHAVES_PUBLICAS]);
 * 2) só então o parse do JSON; 3) o `kid` do payload tem de ser o da chave que conferiu; 4) recusa se
 * `agora >= validoAte` ou `versao` < última aceita (quem chama guarda a última aceita). O cache é verificado de
 * novo a cada leitura. Sem lista válida vale a [EMBUTIDA] (que não destrava host nenhum fora dos nossos).
 *
 * `hosts`: onde a conta pode registrar (servidor e rotas). `dominiosTls`: domínios aceitos no certificado TLS.
 * Os dois aceitam curinga `*.dominio` (um rótulo, como no TLS) e, na lista embutida, `sha256:<hex>` do nome.
 */
object OndafoneLista {
    /**
     * Chaves públicas Ed25519 por `kid` (= 16 primeiros hex do SHA-256 da chave crua, como no givm), em base64 dos
     * 32 bytes. [bloqueado: chave pública nova "Yw4s…" completa] o givm em produção já assina com o kid
     * `0eb49c19091e1f36`; a pública entra aqui (o teste confere que o kid bate). Até lá nenhuma lista do givm é
     * aceita e vale a [EMBUTIDA].
     */
    val CHAVES_PUBLICAS: Map<String, String> = emptyMap()

    const val MAX_BYTES = 16 * 1024
    private const val MAX_ITENS = 200

    data class Lista(
        val versao: Long,
        val validoAteMs: Long,
        val kid: String,
        val hosts: Set<String>,
        val dominiosTls: Set<String>
    )

    /**
     * Cópia embutida (sem validade): os 3 proxies Voipmundo em `*.voipmundo.net`, como SHA-256 do nome (o fonte
     * público, espelho GPL, não cita os servidores internos; `printf %s <host> | sha256sum`).
     */
    val EMBUTIDA = Lista(
        versao = 0,
        validoAteMs = Long.MAX_VALUE,
        kid = "embutida",
        hosts = setOf(
            "sha256:39c9ebc0fdf2ebf6bcfb31fed2c9f236367430294da88ed236cb56dd8f42b33d",
            "sha256:5f6a5531e55c680542293f38b7d920243e4c0562b9edbf0cdaed442778dcc825",
            "sha256:3eca7893ae22b8b50f0a5182709c36621727898f10de799a9dbf4df186181dce"
        ),
        dominiosTls = setOf("*.voipmundo.net")
    )

    sealed class Resultado {
        data class Ok(val lista: Lista) : Resultado()
        data class Rejeitada(val motivo: String) : Resultado()
    }

    private val ROTULO = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
    private val HOST_VALIDO = Regex("^$ROTULO(\\.$ROTULO)+$")

    /** Host em minúsculas, sem ponto final; null se vazio, IP ou inválido (a lista é só por nome). */
    fun normalizarHost(host: String?): String? {
        val h = host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        if (h.isEmpty() || !HOST_VALIDO.matches(h)) return null
        if (h.substringAfterLast('.').all { it.isDigit() }) return null // IPv4
        return h
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hashHost(host: String): String = sha256Hex(host.toByteArray(Charsets.UTF_8))

    /** `kid` de uma chave pública crua (32 bytes): 16 primeiros hex do SHA-256, como no givm. */
    fun kid(chavePublica: ByteArray): String = sha256Hex(chavePublica).take(16)

    /** Item da lista válido: nome, `*.nome` ou `sha256:<64 hex>`. */
    private fun itemValido(item: String): Boolean = when {
        item.startsWith("sha256:") -> item.length == 71 && item.drop(7).all { it in "0123456789abcdef" }
        item.startsWith("*.") -> normalizarHost(item.drop(2)) == item.drop(2)
        else -> normalizarHost(item) == item
    }

    /** true se o host casa com o item: igual, curinga `*.base` (exatamente um rótulo a mais) ou SHA-256 do nome. */
    fun casa(host: String?, item: String): Boolean {
        val h = normalizarHost(host) ?: return false
        return when {
            item.startsWith("sha256:") -> "sha256:${hashHost(h)}" == item
            item.startsWith("*.") -> {
                val base = item.drop(2)
                h.endsWith(".$base") && !h.removeSuffix(".$base").contains('.')
            }
            else -> h == item
        }
    }

    fun casaAlgum(host: String?, itens: Set<String>): Boolean = itens.any { casa(host, it) }

    private fun b64(s: String): ByteArray {
        val n = s.trim().replace('-', '+').replace('_', '/')
        return Base64.getDecoder().decode(n + "=".repeat((4 - n.length % 4) % 4))
    }

    private fun itens(arr: JSONArray?): Set<String>? {
        if (arr == null || arr.length() > MAX_ITENS) return null
        val out = linkedSetOf<String>()
        for (i in 0 until arr.length()) {
            val v = (arr.opt(i) as? String)?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (!itemValido(v)) return null
            out.add(v)
        }
        return out
    }

    /**
     * Verifica o envelope do givm (download novo ou cache). [ultimaVersaoAceita]: versão da última lista aceita
     * (rejeita menor). [chaves]: kid -> chave pública crua (padrão: [CHAVES_PUBLICAS]).
     */
    fun verificar(
        envelope: String?,
        agoraMs: Long,
        ultimaVersaoAceita: Long,
        chaves: Map<String, ByteArray> = chavesEmbutidas()
    ): Resultado {
        if (envelope.isNullOrBlank()) return Resultado.Rejeitada("vazia")
        if (envelope.length > MAX_BYTES) return Resultado.Rejeitada("grande demais")
        val payload: ByteArray
        val assinatura: ByteArray
        try {
            val env = JSONObject(envelope)
            payload = b64(env.getString("payload_b64"))
            assinatura = b64(env.getString("sig_b64"))
        } catch (_: Exception) {
            return Resultado.Rejeitada("envelope inválido")
        }
        if (assinatura.size != 64) return Resultado.Rejeitada("assinatura com tamanho errado")
        if (chaves.isEmpty()) return Resultado.Rejeitada("sem chave pública embutida")
        // 1) Ed25519 sobre os BYTES decodificados, antes de qualquer parse: a chave embutida que conferir é a do kid
        val kidAssinante = chaves.entries.firstOrNull { (_, chave) -> confere(chave, payload, assinatura) }?.key
            ?: return Resultado.Rejeitada("assinatura não confere")
        // 2) só então o JSON; o kid declarado tem de ser o da chave que assinou
        val json = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Exception) {
            return Resultado.Rejeitada("payload inválido")
        }
        val kid = json.opt("kid") as? String
        if (kid == null || kid !in chaves) return Resultado.Rejeitada("kid desconhecido")
        if (kid != kidAssinante) return Resultado.Rejeitada("kid não confere com a chave que assinou")

        // só inteiro de verdade: decimal (Double no Android, BigDecimal no org.json do JVM) ou texto não valem
        val versao = when (val v = json.opt("versao")) {
            is Int -> v.toLong()
            is Long -> v
            else -> null
        }?.takeIf { it >= 1 } ?: return Resultado.Rejeitada("versao não é inteiro positivo")
        if (versao < ultimaVersaoAceita) return Resultado.Rejeitada("versao $versao menor que a aceita ($ultimaVersaoAceita)")
        val validoAteMs = try {
            Instant.parse(json.getString("validoAte")).toEpochMilli()
        } catch (_: Exception) {
            return Resultado.Rejeitada("validoAte inválido")
        }
        if (agoraMs >= validoAteMs) return Resultado.Rejeitada("vencida")
        val hosts = itens(json.optJSONArray("hosts")) ?: return Resultado.Rejeitada("hosts inválido")
        if (hosts.isEmpty()) return Resultado.Rejeitada("sem hosts")
        val dominiosTls = itens(json.optJSONArray("dominiosTls")) ?: return Resultado.Rejeitada("dominiosTls inválido")
        return Resultado.Ok(Lista(versao, validoAteMs, kid, hosts, dominiosTls))
    }

    private fun confere(chave: ByteArray, payload: ByteArray, assinatura: ByteArray): Boolean = try {
        val v = Ed25519Signer()
        v.init(false, Ed25519PublicKeyParameters(chave, 0))
        v.update(payload, 0, payload.size)
        v.verifySignature(assinatura)
    } catch (_: Exception) {
        false
    }

    /**
     * Lista que vale agora: o envelope (cache) se passar em [verificar], senão a [EMBUTIDA]. Se o cache for uma lista
     * assinada que só venceu, a embutida perde os hosts que essa lista já tinha tirado (tirar um proxy da lista não
     * volta atrás quando o givm fica fora do ar).
     */
    fun vigente(
        envelope: String?,
        agoraMs: Long,
        ultimaVersaoAceita: Long,
        chaves: Map<String, ByteArray> = chavesEmbutidas()
    ): Lista {
        (verificar(envelope, agoraMs, ultimaVersaoAceita, chaves) as? Resultado.Ok)?.let { return it.lista }
        val vencida = (verificar(envelope, Long.MIN_VALUE, ultimaVersaoAceita, chaves) as? Resultado.Ok)?.lista
            ?: return EMBUTIDA
        return EMBUTIDA.copy(hosts = EMBUTIDA.hosts.filter { h -> vencida.hosts.any { it == h || casa(it, h) } }.toSet())
    }

    fun chavesEmbutidas(): Map<String, ByteArray> =
        CHAVES_PUBLICAS.mapNotNull { (k, v) ->
            val raw = try { b64(v) } catch (_: Exception) { null }
            if (raw != null && raw.size == 32 && kid(raw) == k) k to raw else null
        }.toMap()
}
