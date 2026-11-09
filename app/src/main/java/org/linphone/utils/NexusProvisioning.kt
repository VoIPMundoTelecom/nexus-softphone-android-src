/*
 * Copyright (c) 2026 Grupo IVM (Nexus / Voipmundo).
 * Based on linphone-android, Copyright (c) 2010-2023 Belledonne Communications SARL.
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

import java.net.URI
import java.net.URISyntaxException

/**
 * Provisionamento remoto do givm (P5 fase 0/1).
 *
 * O givm gera um QR com a URL `https://<host>/api/prov/<token>` (ou `<PROV_BASE_URL>/<token>`)
 * que devolve o XML lpconfig do Linphone com UMA conta SIP; o token e de uso unico.
 * O leitor de QR do assistente ja aceita qualquer URL http(s) (QrCodeViewModel ->
 * LinphoneUtils.getRemoteProvisioningUrlFromUri), entao o QR do givm funciona sem
 * formato especial. Este objeto so reconhece as URLs do givm para o deep link
 * (abrir o link no celular) e para o esquema proprio `nexus-config:`.
 */
object NexusProvisioning {
    /** Esquema proprio para abrir o app pelo link do givm (a pagina do navegador do givm usa este). */
    const val NEXUS_CONFIG_SCHEME = "nexus-config:"

    /**
     * Hosts EXATOS do givm (sem curinga de subdominio). O provisionamento troca a conta SIP do app:
     * de qualquer outro servidor seria um jeito de desviar as chamadas do usuario (revisao 26/10).
     */
    private val GIVM_HOSTS = setOf("api.givm.com.br", "app.givm.com.br")

    private val PROV_PATH = Regex("^/api/prov/[A-Za-z0-9_-]{16,}$")

    // /api/prov/<token> e o legado /prov/<token> (links antigos do givm): os dois mascarados nos logs
    private val TOKEN_IN_PATH = Regex("(/(?:api/)?prov/)([A-Za-z0-9_-]{0,4})[A-Za-z0-9_-]*")

    /**
     * true so para `https://api.givm.com.br/api/prov/<token>` ou `https://app.givm.com.br/api/prov/<token>`:
     * sem http, sem outro host/subdominio, sem usuario@, porta, query, fragmento nem barra final.
     */
    fun isGivmProvisioningUrl(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        val parsed = try {
            URI(uri.trim())
        } catch (_: URISyntaxException) {
            return false
        }
        if (parsed.scheme?.lowercase() != "https") return false
        val host = parsed.host?.lowercase() ?: return false
        if (host !in GIVM_HOSTS) return false
        if (parsed.rawUserInfo != null || (parsed.port != -1 && parsed.port != 443)) return false
        if (parsed.rawQuery != null || parsed.rawFragment != null) return false
        return PROV_PATH.matches(parsed.rawPath ?: "")
    }

    /**
     * URL de provisionamento a aplicar a partir do que veio do QR, do deep link https ou de `nexus-config:<url>`.
     * Devolve null para tudo que nao for o givm (inclusive `linphone-config:`, `file://` e http). Unica porta
     * de entrada: LinphoneUtils.getRemoteProvisioningUrlFromUri.
     */
    fun provisioningUrlFrom(raw: String?): String? {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return null
        val candidate = if (input.startsWith(NEXUS_CONFIG_SCHEME, ignoreCase = true)) {
            val rest = input.substring(NEXUS_CONFIG_SCHEME.length)
            when {
                rest.startsWith("https://", ignoreCase = true) -> rest
                rest.startsWith("//") -> "https:$rest"
                rest.isEmpty() || rest.contains("://") || rest.contains(':') -> return null
                else -> "https://$rest"
            }
        } else {
            input
        }
        return if (isGivmProvisioningUrl(candidate)) candidate.trim() else null
    }

    /** Host mostrado no dialogo de confirmacao antes de aplicar. */
    fun hostOf(url: String): String = try {
        URI(url.trim()).host?.lowercase().orEmpty()
    } catch (_: URISyntaxException) {
        ""
    }

    /** Para log: o token do link vale uma conta SIP, entao so os 4 primeiros caracteres aparecem. */
    fun maskToken(text: String?): String =
        TOKEN_IN_PATH.replace(text.orEmpty()) { m -> m.groupValues[1] + m.groupValues[2] + "…" }

    /**
     * true se a URL aponta para um servidor da Belledonne (linphone.org e subdominios): upload de logs,
     * transferencia de arquivos do chat, checagem de versao. O Nexus nao manda nada para la; o CoreContext
     * limpa essas URLs no start (inclusive as que migracoes do upstream gravariam).
     */
    fun isBelledonneServerUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try {
            URI(url.trim()).host?.lowercase()
        } catch (_: URISyntaxException) {
            null
        } ?: return false
        return host == "linphone.org" || host.endsWith(".linphone.org")
    }

    /**
     * true se o servidor STUN/TURN (`host`, `host:porta` ou `stun:host`) e da Belledonne (ex.: stun.linphone.org,
     * padrao do upstream para contas SIP manuais). O CoreContext tira esse STUN das contas no start.
     */
    fun isBelledonneHost(server: String?): Boolean {
        if (server.isNullOrBlank()) return false
        val host = server.trim().lowercase()
            .removePrefix("stuns:").removePrefix("stun:").removePrefix("turns:").removePrefix("turn:")
            .removePrefix("//").substringBefore(':').substringBefore('/')
        return host == "linphone.org" || host.endsWith(".linphone.org")
    }

    /**
     * true se a conta tem ICE ligado sem STUN proprio (vazio ou da Belledonne, que o Nexus remove): o ICE so
     * ofereceria candidatos locais e, atras de NAT, o audio fica de mao unica. Nesse caso ICE e STUN saem.
     */
    fun shouldDisableIce(stunServer: String?, iceEnabled: Boolean): Boolean =
        iceEnabled && (stunServer.isNullOrBlank() || isBelledonneHost(stunServer))

    /**
     * Liga o servico em primeiro plano (keep-alive) uma unica vez quando ha conta sem push.
     * Sem o Firebase do Grupo IVM nenhuma conta tem push, e a conta do QR/link do givm entra no
     * reinicio do Core do provisionamento remoto, quando o upstream ignora o onAccountAdded (que e
     * quem liga o keep-alive). Sem isso o app fechado nao recebe chamada. Uma vez so: se o usuario
     * desligar depois em Configuracoes, a escolha dele vale.
     */
    fun shouldAutoEnableKeepAlive(
        keepAliveEnabled: Boolean,
        alreadyAutoEnabled: Boolean,
        accountsWithoutPush: Int
    ): Boolean = !keepAliveEnabled && !alreadyAutoEnabled && accountsWithoutPush > 0
}
