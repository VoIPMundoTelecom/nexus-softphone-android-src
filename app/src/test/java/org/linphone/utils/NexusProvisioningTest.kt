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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusProvisioningTest {
    // formato de secrets.token_urlsafe(32) do givm (43 chars, [A-Za-z0-9_-])
    private val token = "q5mJ0b0zv3Yk-9d3xW_7rT1uYbqk2L8nZsA0cV4eF6g" // gitleaks:allow (token de exemplo, não é segredo)

    @Test
    fun aceitaUrlsDoGivm() {
        assertTrue(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br/api/prov/$token"))
        assertTrue(NexusProvisioning.isGivmProvisioningUrl("https://app.givm.com.br/api/prov/$token"))
        assertTrue(NexusProvisioning.isGivmProvisioningUrl(" https://API.givm.com.br/api/prov/$token "))
        assertTrue(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br:443/api/prov/$token"))
    }

    @Test
    fun recusaOutrasUrls() {
        assertFalse(NexusProvisioning.isGivmProvisioningUrl(null))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl(""))
        // revisao 26/10: sem http, sem curinga de subdominio, so /api/prov/<token>
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("http://api.givm.com.br/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://evil.givm.com.br/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://givm.com.br/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://givm.com.br.evil.com/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br.evil.com/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://app.givm.com.br.evil.com/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://evilgivm.com.br/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br/api/clientes/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br/api/prov/curto"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br/api/prov/$token/../../x"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br/api/prov/$token?u=https://evil.com"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br/api/prov/$token#x"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://evil.com@api.givm.com.br/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br:8443/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("ftp://api.givm.com.br/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("sip:1000@api.givm.com.br"))
    }

    @Test
    fun recusaBarraFinalHostComArrobaPontoFinalEIdn() {
        // revisao 27/10
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://app.givm.com.br/api/prov/$token/"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br@evil.com/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://api.givm.com.br./api/prov/$token"))
        // "а" cirilico (U+0430) no lugar do "a"
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://\u0430pi.givm.com.br/api/prov/$token"))
        assertFalse(NexusProvisioning.isGivmProvisioningUrl("https://xn--pi-7kc.givm.com.br/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:api.givm.com.br@evil.com/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config://api.givm.com.br@evil.com/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:https://api.givm.com.br@evil.com/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:api.givm.com.br./api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:\u0430pi.givm.com.br/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("https://api.givm.com.br/api/prov/$token/"))
    }

    @Test
    fun entradaDoQrELinks() {
        val url = "https://api.givm.com.br/api/prov/$token"
        assertEquals(url, NexusProvisioning.provisioningUrlFrom(url))
        assertEquals(url, NexusProvisioning.provisioningUrlFrom(" $url "))
        assertEquals(url, NexusProvisioning.provisioningUrlFrom("nexus-config:$url"))
        assertEquals(url, NexusProvisioning.provisioningUrlFrom("NEXUS-CONFIG:$url"))
        assertEquals(url, NexusProvisioning.provisioningUrlFrom("nexus-config://api.givm.com.br/api/prov/$token"))
        assertEquals(url, NexusProvisioning.provisioningUrlFrom("nexus-config:api.givm.com.br/api/prov/$token"))
        // qualquer outro servidor, http, file:// e linphone-config: sao recusados
        assertNull(NexusProvisioning.provisioningUrlFrom(null))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:https://evil.com/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:http://api.givm.com.br/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:file:///sdcard/x.xml"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:evil.com/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("nexus-config:api.givm.com.br.evil.com/api/prov/$token"))
        assertNull(NexusProvisioning.provisioningUrlFrom("linphone-config:$url"))
        assertNull(NexusProvisioning.provisioningUrlFrom("linphone-config:file:///sdcard/x.xml"))
        assertNull(NexusProvisioning.provisioningUrlFrom("file:///sdcard/x.xml"))
        assertNull(NexusProvisioning.provisioningUrlFrom("https://example.com/config.xml"))
    }

    @Test
    fun hostEMascaraDoToken() {
        assertEquals("api.givm.com.br", NexusProvisioning.hostOf("https://API.givm.com.br/api/prov/$token"))
        val masked = NexusProvisioning.maskToken("nexus-config:https://api.givm.com.br/api/prov/$token")
        assertEquals("nexus-config:https://api.givm.com.br/api/prov/q5mJ…", masked)
        assertFalse(masked.contains(token))
        // legado /prov/<token> tambem sai mascarado
        val legado = NexusProvisioning.maskToken("https://api.givm.com.br/prov/$token")
        assertEquals("https://api.givm.com.br/prov/q5mJ…", legado)
        assertFalse(legado.contains(token))
        assertEquals("", NexusProvisioning.maskToken(null))
        assertEquals("sip:1000@x", NexusProvisioning.maskToken("sip:1000@x"))
    }

    @Test
    fun servidoresBelledonne() {
        assertTrue(NexusProvisioning.isBelledonneServerUrl("https://files.linphone.org/http-file-transfer-server/hft.php"))
        assertTrue(NexusProvisioning.isBelledonneServerUrl("https://www.linphone.org:444/lft.php"))
        assertTrue(NexusProvisioning.isBelledonneServerUrl("https://download.linphone.org/releases"))
        assertTrue(NexusProvisioning.isBelledonneServerUrl(" https://LINPHONE.org/x "))
        assertFalse(NexusProvisioning.isBelledonneServerUrl(null))
        assertFalse(NexusProvisioning.isBelledonneServerUrl(""))
        assertFalse(NexusProvisioning.isBelledonneServerUrl("https://api.givm.com.br/logs"))
        assertFalse(NexusProvisioning.isBelledonneServerUrl("https://linphone.org.evil.com/x"))
        assertFalse(NexusProvisioning.isBelledonneServerUrl("https://notlinphone.org/x"))
    }

    @Test
    fun stunBelledonne() {
        assertTrue(NexusProvisioning.isBelledonneHost("stun.linphone.org"))
        assertTrue(NexusProvisioning.isBelledonneHost(" STUN.linphone.org:3478 "))
        assertTrue(NexusProvisioning.isBelledonneHost("stun:stun.linphone.org"))
        assertFalse(NexusProvisioning.isBelledonneHost(null))
        assertFalse(NexusProvisioning.isBelledonneHost(""))
        assertFalse(NexusProvisioning.isBelledonneHost("stun.l.google.com:19302"))
        assertFalse(NexusProvisioning.isBelledonneHost("stun.linphone.org.evil.com"))
        assertFalse(NexusProvisioning.isBelledonneHost("notlinphone.org"))
    }

    @Test
    fun iceSemStunDesliga() {
        assertTrue(NexusProvisioning.shouldDisableIce("", true))
        assertTrue(NexusProvisioning.shouldDisableIce(null, true))
        assertTrue(NexusProvisioning.shouldDisableIce("stun.linphone.org", true))
        assertFalse(NexusProvisioning.shouldDisableIce("stun.voipmundo.com.br:3478", true))
        assertFalse(NexusProvisioning.shouldDisableIce("", false))
    }

    @Test
    fun keepAliveAutomaticoSoUmaVez() {
        // conta do QR sem push, keep-alive desligado, nunca ligado antes -> liga
        assertTrue(NexusProvisioning.shouldAutoEnableKeepAlive(false, false, 1))
        // sem conta (antes do QR) -> nao liga
        assertFalse(NexusProvisioning.shouldAutoEnableKeepAlive(false, false, 0))
        // ja ligado -> nada a fazer
        assertFalse(NexusProvisioning.shouldAutoEnableKeepAlive(true, false, 1))
        // ja ligado automaticamente antes e o usuario desligou -> respeita
        assertFalse(NexusProvisioning.shouldAutoEnableKeepAlive(false, true, 1))
    }
}
