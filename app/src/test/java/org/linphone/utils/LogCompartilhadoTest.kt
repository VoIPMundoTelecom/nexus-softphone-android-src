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

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.utils.LogCompartilhado.mascarar

class LogCompartilhadoTest {
    private val segredo = "S3gr3d0-Ab"

    private fun semSegredo(linha: String, vararg segredos: String = arrayOf(segredo)) {
        val m = mascarar(linha)
        segredos.forEach { assertFalse("vazou [$it] em: $m", m.contains(it)) }
        assertTrue("sem máscara em: $m", m.contains(LogCompartilhado.MASCARA))
    }

    @Test
    fun `senha e ha1 do linphonerc e do provisionamento`() {
        semSegredo("passwd=$segredo")
        semSegredo("ha1 = 0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef")
        semSegredo("""<entry name="passwd" overwrite="true">$segredo</entry>""")
        semSegredo("""<entry name="ha1">$segredo</entry>""")
        semSegredo("<passwd>$segredo</passwd>")
        semSegredo("<password type=\"x\">$segredo</password>")
    }

    @Test
    fun `json e cabecalhos`() {
        semSegredo("""{"email":"a@b.c","token":"$segredo","x":1}""")
        semSegredo("""{"session_token" : "$segredo"}""")
        semSegredo("Authorization: Bearer $segredo")
        semSegredo("Authorization: Basic dXNlcjpwYXNzd29yZA==", "dXNlcjpwYXNzd29yZA==")
        semSegredo("got eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl ok", "eyJhbGciOiJFZERTQSJ9")
        semSegredo("X-Api-Key: $segredo")
        semSegredo("senha: '$segredo'")
    }

    @Test
    fun `digest mascara response e cnonce e mantem o resto para a triagem`() {
        val linha = "Authorization: Digest realm=\"r1\", nonce=\"abc123\", algorithm=MD5, opaque=\"op\", " +
            "username=\"1000\", uri=\"sip:2000@h\", response=\"0f1e2d3c4b5a69788796a5b4c3d2e1f0\", " +
            "cnonce=\"zzzz9999\", nc=00000001, qop=auth"
        val m = mascarar(linha)
        assertFalse(m.contains("0f1e2d3c4b5a69788796a5b4c3d2e1f0"))
        assertFalse(m.contains("zzzz9999"))
        listOf("realm=\"r1\"", "nonce=\"abc123\"", "username=\"1000\"", "qop=auth", "algorithm=MD5").forEach {
            assertTrue("sumiu [$it]: $m", m.contains(it))
        }
    }

    @Test
    fun `push provisionamento e tokens soltos`() {
        semSegredo("Contact: <sip:1000@203.0.113.1;pn-provider=fcm;pn-prid=$segredo;pn-param=abc>")
        semSegredo("GET https://api.exemplo.com/prov/$segredo -> 200")
        semSegredo("We've already have a token [$segredo], continuing")
        val fcm = "dK3x" + "a".repeat(140) + ":APA91b"
        semSegredo("Token fetched from Firebase: $fcm", fcm)
        val aleatorio = "Zx9" + "k2".repeat(20)
        semSegredo("https://h/a/$aleatorio/b", aleatorio)
    }

    @Test
    fun `entradas da revisao`() {
        semSegredo("""Push received: [{"custom-payload":"{\"token\":\"$segredo\"}"}]""")
        semSegredo("""{\"password\":\"$segredo\"}""")
        val seg = "AbCdEf0123456789AbCdEf0123456789"
        semSegredo("Trying to open [https://h/recovery/phone/$seg] URL", seg)
        semSegredo("https://h/a/$seg;x", seg)
        semSegredo("https://h/a/$seg)", seg)
        semSegredo("Account recovery token received [$segredo], opening browser")
        semSegredo("Password is [$segredo]")
        semSegredo("""<entry overwrite="true" name="passwd">$segredo</entry>""")
        semSegredo("<entry name='passwd'>$segredo</entry>")
        semSegredo("passwd=pa ss;$segredo")
        semSegredo("user=1000 domain=h passwd=pa ss;$segredo")
        semSegredo("a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:$segredo|2^31", segredo)
        semSegredo("Cookie: PHPSESSID=$segredo")
    }

    @Test
    fun `branch e tag do PJSIP e texto comum nao sao mascarados`() {
        listOf(
            "Via: SIP/2.0/UDP 203.0.113.1:5060;rport;branch=z9hG4bKPj5b3c0c2a-1f0e-4f6a-9d6e-3b2a1c0d9e8f",
            "To: <sip:1000@x>;tag=5c1f7c8d-2b43-4e0f-9a1b-0c2d3e4f5a6b",
            "Using basic settings for profile",
            "Session-Expires: 1800"
        ).forEach { assertEquals(it, mascarar(it)) }
    }

    @Test(timeout = 5_000)
    fun `linha longa e maliciosa nao trava`() {
        mascarar("token-".repeat(3_000))
        mascarar("a-".repeat(8_000))
        val enorme = "x".repeat(LogCompartilhado.MAX_CHARS_LINHA * 4)
        assertTrue(mascarar(enorme).length < LogCompartilhado.MAX_CHARS_LINHA + 100)
    }

    @Test
    fun `log comum fica igual`() {
        listOf(
            "2026-11-07 10:00:00:123 liblinphone-message-Account [0x1] registration state changed to Ok",
            "INVITE sip:2000@proxy.exemplo.net SIP/2.0",
            "Via: SIP/2.0/TCP 203.0.113.2:5060;branch=z9hG4bK.abc;rport",
            "SIP/2.0 401 Unauthorized",
            "WWW-Authenticate: Digest realm=\"r1\", nonce=\"abc\", qop=\"auth\", algorithm=MD5",
            "username=1000 domain=proxy.exemplo.net transport=tcp",
            "algorithm sha1 negotiated",
            "Call-ID: 8f2kL0s-x"
        ).forEach { assertEquals(it, mascarar(it)) }
    }

    @Test
    fun `gera zip mascarado com os arquivos do mais velho para o mais novo e respeita o teto`() {
        val pasta = Files.createTempDirectory("logs").toFile()
        try {
            val velho = File(pasta, "linphone2.log").apply { writeText("velho passwd=$segredo\n") }
            val novo = File(pasta, "linphone1.log").apply { writeText("novo linha\noutra\n") }
            File(pasta, "outro.txt").writeText("não é log")
            velho.setLastModified(1_000_000L)
            novo.setLastModified(2_000_000L)

            val arquivos = LogCompartilhado.arquivosDeLog(pasta)
            assertEquals(listOf("linphone2.log", "linphone1.log"), arquivos.map { it.name })
            assertEquals(listOf("linphone1.log"), LogCompartilhado.dentroDoTeto(arquivos, maxBytes = 20).map { it.name })
            assertEquals(listOf("linphone1.log"), LogCompartilhado.dentroDoTeto(listOf(novo), maxBytes = 1).map { it.name })

            val zip = File(pasta, "saida/x.zip")
            val linhas = LogCompartilhado.gerar(arquivos, zip, "Versão: 6.3.0\ntoken=$segredo")
            assertEquals(3, linhas)
            val texto = ZipFile(zip).use { z ->
                z.getInputStream(z.getEntry(LogCompartilhado.NOME_TEXTO)).readBytes().toString(Charsets.UTF_8)
            }
            assertFalse(texto.contains(segredo))
            assertTrue(texto.indexOf("velho") < texto.indexOf("novo linha"))
            assertTrue(texto.startsWith("Versão: 6.3.0"))
        } finally {
            pasta.deleteRecursively()
        }
    }
}
