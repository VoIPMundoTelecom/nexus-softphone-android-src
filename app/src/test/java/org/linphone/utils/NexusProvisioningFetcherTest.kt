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

class NexusProvisioningFetcherTest {
    private val token = "q5mJ0b0zv3Yk-9d3xW_7rT1uYbqk2L8nZsA0cV4eF6g" // gitleaks:allow (token de exemplo, não é segredo)
    private val url = "https://api.givm.com.br/api/prov/$token"

    @Test
    fun redirectSoParaOutraUrlDoGivm() {
        val outra = "https://app.givm.com.br/api/prov/$token"
        assertEquals(outra, NexusProvisioningFetcher.redirectTarget(url, outra))
        // relativo, resolvido contra a URL atual
        assertEquals(url, NexusProvisioningFetcher.redirectTarget(url, "/api/prov/$token"))
        // qualquer outro destino e recusado (revisao 27/10: nada de seguir redirect para fora do givm)
        assertNull(NexusProvisioningFetcher.redirectTarget(url, null))
        assertNull(NexusProvisioningFetcher.redirectTarget(url, ""))
        assertNull(NexusProvisioningFetcher.redirectTarget(url, "https://evil.com/api/prov/$token"))
        assertNull(NexusProvisioningFetcher.redirectTarget(url, "http://api.givm.com.br/api/prov/$token"))
        assertNull(NexusProvisioningFetcher.redirectTarget(url, "//evil.com/api/prov/$token"))
        assertNull(NexusProvisioningFetcher.redirectTarget(url, "https://api.givm.com.br@evil.com/api/prov/$token"))
        assertNull(NexusProvisioningFetcher.redirectTarget(url, "/api/clientes"))
        assertNull(NexusProvisioningFetcher.redirectTarget(url, "file:///sdcard/x.xml"))
        assertNull(NexusProvisioningFetcher.redirectTarget(url, "linphone-config:https://evil.com/x.xml"))
    }

    @Test
    fun corpoPrecisaSerLpconfig() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<config xmlns=\"http://www.linphone.org/xsds/lpconfig.xsd\">\n" +
            "  <section name=\"proxy_0\"><entry name=\"reg_proxy\">x</entry></section>\n" +
            "</config>\n"
        assertTrue(NexusProvisioningFetcher.looksLikeLpconfig(xml))
        assertTrue(NexusProvisioningFetcher.looksLikeLpconfig("\uFEFF" + xml))
        assertTrue(NexusProvisioningFetcher.looksLikeLpconfig("<config></config>"))
        assertFalse(NexusProvisioningFetcher.looksLikeLpconfig(""))
        assertFalse(NexusProvisioningFetcher.looksLikeLpconfig("<html><body>login</body></html>"))
        assertFalse(NexusProvisioningFetcher.looksLikeLpconfig("{\"detail\":\"Token inválido\"}"))
        assertFalse(NexusProvisioningFetcher.looksLikeLpconfig("<?xml version=\"1.0\"?><html></html>"))
        assertFalse(NexusProvisioningFetcher.looksLikeLpconfig("<config>"))
    }

    @Test
    fun contentTypePrecisaSerXml() {
        assertTrue(NexusProvisioningFetcher.isXmlContentType("application/xml"))
        assertTrue(NexusProvisioningFetcher.isXmlContentType("application/xml; charset=utf-8"))
        assertTrue(NexusProvisioningFetcher.isXmlContentType("TEXT/XML"))
        assertFalse(NexusProvisioningFetcher.isXmlContentType(null))
        assertFalse(NexusProvisioningFetcher.isXmlContentType(""))
        // a pagina do givm para navegador (UA que nao e de app)
        assertFalse(NexusProvisioningFetcher.isXmlContentType("text/html; charset=utf-8"))
        assertFalse(NexusProvisioningFetcher.isXmlContentType("application/json"))
        assertFalse(NexusProvisioningFetcher.isXmlContentType("text/plain; note=xml"))
    }

    @Test
    fun naoBaixaUrlForaDoGivm() {
        // recusado antes de abrir conexao
        for (u in listOf("https://evil.com/api/prov/$token", "http://api.givm.com.br/api/prov/$token", "file:///x.xml", "")) {
            val result = NexusProvisioningFetcher.fetch(u, "nexus-app/test")
            assertTrue(result is NexusProvisioningFetcher.Result.Error)
        }
    }
}
