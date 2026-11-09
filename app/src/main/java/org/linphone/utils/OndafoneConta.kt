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

import org.json.JSONObject

/**
 * Conta do app no givm (back #390): entrar por e-mail + código de 6 dígitos (cadastro e login são o mesmo fluxo),
 * sair e excluir a conta. Regras puras e corpos das rotas (testadas em OndafoneContaTest).
 */
object OndafoneConta {
    /** Versão dos termos aceita no cadastro (o givm só confere se ONDAFONE_TERMOS_VERSAO estiver definida). */
    const val TERMOS_VERSAO = "2026-11"
    const val ORIGEM = "app-android"

    private val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+$")

    fun normalizarEmail(bruto: String?): String = bruto.orEmpty().trim().lowercase()

    fun emailValido(bruto: String?): Boolean {
        val e = normalizarEmail(bruto)
        return e.length in 6..254 && EMAIL.matches(e)
    }

    /** Teto do `dispositivo.aparelhoId` no givm. */
    const val APARELHO_ID_MAX = 128

    /**
     * Id estável desta instalação (UUID aleatório, sem dado do aparelho nem do usuário). O givm usa só o HMAC dele
     * para o limite de contas novas por aparelho; com ONDAFONE_EXIGIR_APARELHO, conta nova sem ele é recusada.
     */
    fun novoAparelhoId(): String = java.util.UUID.randomUUID().toString()

    fun aparelhoIdValido(id: String?): Boolean = !id.isNullOrBlank() && id.length <= APARELHO_ID_MAX

    /** `dispositivo` das rotas de sessão: plataforma, versão do app e o id da instalação. */
    fun dispositivo(versaoApp: String?, aparelhoId: String?): JSONObject {
        val d = JSONObject().put("plataforma", "android")
        if (!versaoApp.isNullOrBlank()) d.put("versao", versaoApp.take(30))
        if (aparelhoIdValido(aparelhoId)) d.put("aparelhoId", aparelhoId)
        return d
    }

    fun normalizarCodigo(bruto: String?): String = bruto.orEmpty().filter { it.isDigit() }

    fun codigoValido(bruto: String?): Boolean = normalizarCodigo(bruto).length == 6

    /** POST /ondafone/cadastro: conta nova exige o aceite; o consentimento de marketing é separado (padrão não). */
    fun corpoCadastro(email: String, aceite: Boolean, marketing: Boolean): String {
        val o = JSONObject().put("email", normalizarEmail(email)).put("origem", ORIGEM).put("marketing", marketing)
        if (aceite) o.put("aceiteTermos", true).put("versaoTermos", TERMOS_VERSAO)
        return o.toString()
    }

    fun corpoConfirmar(email: String, codigo: String, versaoApp: String, aparelhoId: String? = null): String =
        JSONObject()
            .put("email", normalizarEmail(email))
            .put("codigo", normalizarCodigo(codigo))
            .put("dispositivo", dispositivo(versaoApp, aparelhoId))
            .toString()

    /**
     * POST /ondafone/login/google: o ID token do Google (o givm valida assinatura, `aud`, `iss`, validade e
     * `email_verified`; o e-mail vem só do token). Conta nova exige o aceite, como no e-mail.
     */
    fun corpoGoogle(
        idToken: String,
        aceite: Boolean,
        marketing: Boolean,
        versaoApp: String,
        aparelhoId: String? = null
    ): String {
        val o = JSONObject()
            .put("idToken", idToken)
            .put("origem", ORIGEM)
            .put("marketing", marketing)
            .put("dispositivo", dispositivo(versaoApp, aparelhoId))
        if (aceite) o.put("aceiteTermos", true).put("versaoTermos", TERMOS_VERSAO)
        return o.toString()
    }

    /** `usuario.email` da resposta do login (o givm devolve a conta junto da sessão). */
    fun emailDaResposta(json: String?): String? = try {
        val u = JSONObject(json ?: "").optJSONObject("usuario")
        if (u == null || u.isNull("email")) null else u.optString("email").ifBlank { null }?.let { normalizarEmail(it) }
    } catch (_: Exception) {
        null
    }

    fun corpoSair(todos: Boolean): String = JSONObject().put("todos", todos).toString()
}
