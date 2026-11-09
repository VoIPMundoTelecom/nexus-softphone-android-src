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

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.utils.OndafoneLista.Resultado

class OndafoneListaTest {
    private val agora = 1_800_000_000_000L // 2027-01-15T08:00:00Z
    private val priv = Ed25519PrivateKeyParameters(SecureRandom())
    private val pub = priv.generatePublicKey().encoded
    private val kid = OndafoneLista.kid(pub)
    private val chaves = mapOf(kid to pub)

    private fun payload(
        versao: Any = 7,
        validoAte: String = "2027-01-16T08:00:00Z",
        k: String = kid,
        hosts: List<Any> = listOf("proxy-a.voipmundo.net", "proxy-b.voipmundo.net"),
        dominiosTls: List<Any> = listOf("*.voipmundo.net")
    ): ByteArray = JSONObject()
        .put("versao", versao).put("validoAte", validoAte).put("kid", k)
        .put("hosts", org.json.JSONArray(hosts)).put("dominiosTls", org.json.JSONArray(dominiosTls))
        .toString().toByteArray(Charsets.UTF_8)

    private fun assinar(p: ByteArray, chave: Ed25519PrivateKeyParameters = priv): ByteArray {
        val s = Ed25519Signer()
        s.init(true, chave)
        s.update(p, 0, p.size)
        return s.generateSignature()
    }

    private fun envelope(p: ByteArray, sig: ByteArray = assinar(p), url: Boolean = false): String {
        val enc = if (url) Base64.getUrlEncoder().withoutPadding() else Base64.getEncoder()
        return JSONObject().put("payload_b64", enc.encodeToString(p)).put("sig_b64", enc.encodeToString(sig)).toString()
    }

    private fun verificar(env: String?, ultima: Long = 0, quando: Long = agora) =
        OndafoneLista.verificar(env, quando, ultima, chaves)

    private fun motivo(r: Resultado) = (r as Resultado.Rejeitada).motivo

    @Test
    fun `lista assinada valida e aceita`() {
        val r = verificar(envelope(payload()))
        assertTrue(r is Resultado.Ok)
        val l = (r as Resultado.Ok).lista
        assertEquals(7L, l.versao)
        assertEquals(kid, l.kid)
        assertEquals(setOf("proxy-a.voipmundo.net", "proxy-b.voipmundo.net"), l.hosts)
        assertEquals(setOf("*.voipmundo.net"), l.dominiosTls)
        // base64url sem '=' também
        assertTrue(verificar(envelope(payload(), url = true)) is Resultado.Ok)
    }

    @Test
    fun `payload adulterado ou assinado por outra chave e rejeitado`() {
        val p = payload()
        val adulterado = payload(hosts = listOf("proxy-a.voipmundo.net", "sip.atacante.com"))
        assertEquals("assinatura não confere", motivo(verificar(envelope(adulterado, assinar(p)))))
        val outra = Ed25519PrivateKeyParameters(SecureRandom())
        assertEquals("assinatura não confere", motivo(verificar(envelope(p, assinar(p, outra)))))
    }

    @Test
    fun `kid desconhecido e rejeitado`() {
        // assinada pela chave embutida, mas declarando outro kid
        assertEquals("kid desconhecido", motivo(verificar(envelope(payload(k = "0123456789abcdef")))))
        // assinada por uma chave que o app não tem, com o kid dela
        val outra = Ed25519PrivateKeyParameters(SecureRandom())
        val p = payload(k = OndafoneLista.kid(outra.generatePublicKey().encoded))
        assertEquals("assinatura não confere", motivo(verificar(envelope(p, assinar(p, outra)))))
        // duas chaves embutidas: o kid declarado tem de ser o da que assinou
        val outraPub = outra.generatePublicKey().encoded
        val duas = mapOf(kid to pub, OndafoneLista.kid(outraPub) to outraPub)
        val trocado = payload(k = OndafoneLista.kid(outraPub))
        assertEquals(
            "kid não confere com a chave que assinou",
            motivo(OndafoneLista.verificar(envelope(trocado), agora, 0, duas))
        )
        // sem chave embutida nenhuma lista do givm vale
        assertEquals("sem chave pública embutida", motivo(OndafoneLista.verificar(envelope(payload()), agora, 0, emptyMap())))
    }

    @Test
    fun `assinatura e conferida nos bytes antes do parse`() {
        // payload que não é JSON, mas assinado: passa da assinatura e só então cai no parse
        val lixo = "nao e json".toByteArray()
        assertEquals("payload inválido", motivo(verificar(envelope(lixo))))
        // mesmo JSON reserializado (outros bytes) com a assinatura do original: recusado
        val p = payload()
        val reserializado = JSONObject(String(p)).toString(2).toByteArray()
        assertEquals("assinatura não confere", motivo(verificar(envelope(reserializado, assinar(p)))))
    }

    @Test
    fun `lista recusada deixa o app no basico`() {
        val hostDaLista = OndafoneModo.Salto("proxy-a.voipmundo.net", "udp")
        val ok = OndafoneLista.vigente(envelope(payload()), agora, 0, chaves)
        assertTrue(OndafoneModo.qualifica(hostDaLista, emptyList(), false, false, ok))
        val outra = Ed25519PrivateKeyParameters(SecureRandom())
        val p = payload()
        listOf(
            envelope(p, assinar(p, outra)) to 0L, // assinatura inválida
            envelope(payload(k = "0123456789abcdef")) to 0L, // kid desconhecido
            envelope(payload(validoAte = "2027-01-15T08:00:00Z")) to 0L, // vencida (agora == validoAte)
            envelope(payload(versao = 6)) to 7L // versão menor que a última aceita
        ).forEach { (env, ultima) ->
            val l = OndafoneLista.vigente(env, agora, ultima, chaves)
            assertEquals("embutida", l.kid)
            assertTrue(OndafoneLista.EMBUTIDA.hosts.containsAll(l.hosts))
            val q = OndafoneModo.qualifica(hostDaLista, emptyList(), false, false, l)
            assertFalse(q)
            assertEquals(
                OndafoneModo.Modo.LIMITADO,
                OndafoneModo.modo(listOf(OndafoneModo.Conta(q, agora - 60_000)), agora)
            )
        }
    }

    @Test
    fun `lista vencida e rejeitada`() {
        assertEquals("vencida", motivo(verificar(envelope(payload(validoAte = "2027-01-15T08:00:00Z")))))
        assertEquals("vencida", motivo(verificar(envelope(payload()), quando = agora + 25 * 3_600_000L)))
        assertEquals("validoAte inválido", motivo(verificar(envelope(payload(validoAte = "amanhã")))))
    }

    @Test
    fun `versao menor que a ultima aceita e rejeitada`() {
        assertTrue(verificar(envelope(payload(versao = 7)), ultima = 7) is Resultado.Ok) // a mesma lista de novo
        assertTrue(verificar(envelope(payload(versao = 8)), ultima = 7) is Resultado.Ok)
        assertEquals("versao 6 menor que a aceita (7)", motivo(verificar(envelope(payload(versao = 6)), ultima = 7)))
        assertEquals("versao não é inteiro positivo", motivo(verificar(envelope(payload(versao = "sha256:abc")))))
        assertEquals("versao não é inteiro positivo", motivo(verificar(envelope(payload(versao = 7.5)))))
        assertEquals("versao não é inteiro positivo", motivo(verificar(envelope(payload(versao = 0)))))
    }

    @Test
    fun `itens invalidos rejeitam a lista inteira`() {
        listOf<List<Any>>(
            listOf("proxy-a.voipmundo.net", "1.2.3.4"),
            listOf("proxy-a.voipmundo.net", 5),
            listOf("*.*.voipmundo.net"),
            listOf("proxy a.voipmundo.net"),
            emptyList()
        ).forEach { hosts ->
            assertTrue("$hosts", verificar(envelope(payload(hosts = hosts))) is Resultado.Rejeitada)
        }
        assertTrue(verificar(envelope(payload(dominiosTls = listOf("*.")))) is Resultado.Rejeitada)
    }

    @Test
    fun `envelope malformado e rejeitado`() {
        assertEquals("vazia", motivo(verificar(null)))
        assertEquals("vazia", motivo(verificar("")))
        assertEquals("envelope inválido", motivo(verificar("<html>erro</html>")))
        assertEquals("envelope inválido", motivo(verificar("""{"payload_b64":"e30"}""")))
        assertEquals("assinatura com tamanho errado", motivo(verificar(envelope(payload(), ByteArray(10)))))
        assertEquals("grande demais", motivo(verificar("x".repeat(OndafoneLista.MAX_BYTES + 1))))
    }

    @Test
    fun `curinga casa um rotulo so`() {
        assertTrue(OndafoneLista.casa("proxy-a.voipmundo.net", "*.voipmundo.net"))
        assertTrue(OndafoneLista.casa("PROXY-A.VoipMundo.net.", "*.voipmundo.net"))
        assertFalse(OndafoneLista.casa("voipmundo.net", "*.voipmundo.net"))
        assertFalse(OndafoneLista.casa("a.b.voipmundo.net", "*.voipmundo.net"))
        assertFalse(OndafoneLista.casa("proxy-a.voipmundo.net.evil.com", "*.voipmundo.net"))
        assertFalse(OndafoneLista.casa("evilvoipmundo.net", "*.voipmundo.net"))
    }

    @Test
    fun `item por SHA-256 confere o host normalizado`() {
        val item = "sha256:" + OndafoneLista.hashHost("proxy-c.voipmundo.net")
        assertTrue(OndafoneLista.casa("Proxy-C.VoipMundo.net.", item))
        assertFalse(OndafoneLista.casa("proxy-c.voipmundo.net.evil.com", item))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", // SHA-256 de ""
            OndafoneLista.hashHost("")
        )
    }

    @Test
    fun `lista embutida so com SHA-256 dos proxies e dominio TLS voipmundo`() {
        val e = OndafoneLista.EMBUTIDA
        assertEquals(3, e.hosts.size)
        assertTrue(e.hosts.all { it.matches(Regex("sha256:[0-9a-f]{64}")) })
        assertEquals(setOf("*.voipmundo.net"), e.dominiosTls)
        assertFalse(OndafoneLista.casaAlgum("sip.terceiro.com", e.hosts))
    }

    @Test
    fun `chaves embutidas tem kid coerente`() {
        // hoje vazia ([bloqueado: par de chaves]); quando a pública entrar, o kid tem de ser o do givm
        OndafoneLista.CHAVES_PUBLICAS.forEach { (k, v) ->
            val raw = Base64.getDecoder().decode(v)
            assertEquals(32, raw.size)
            assertEquals(k, OndafoneLista.kid(raw))
        }
        assertEquals(OndafoneLista.CHAVES_PUBLICAS.size, OndafoneLista.chavesEmbutidas().size)
        assertEquals(16, kid.length)
    }

    @Test
    fun `lista vencida nao devolve host que ela tinha tirado`() {
        val embutidos = listOf("proxy-a.voipmundo.net", "proxy-b.voipmundo.net", "proxy-c.voipmundo.net")
        val embutida = OndafoneLista.EMBUTIDA
        val vencida = envelope(payload(validoAte = "2027-01-15T07:00:00Z", hosts = listOf("proxy-a.voipmundo.net")))
        val l = OndafoneLista.vigente(vencida, agora, 0, chaves)
        assertEquals("embutida", l.kid)
        assertTrue(embutida.hosts.containsAll(l.hosts))
        // a lista vencida não traz nenhum dos embutidos: nenhum volta
        assertTrue(embutidos.none { h -> OndafoneLista.casaAlgum(h, l.hosts) })
        // a lista vencida ainda tem 1 dos embutidos (pelo hash): só ele fica
        val um = embutida.hosts.first()
        val comUm = envelope(payload(validoAte = "2027-01-15T07:00:00Z", hosts = listOf(um, "proxy-a.voipmundo.net")))
        assertEquals(setOf(um), OndafoneLista.vigente(comUm, agora, 0, chaves).hosts)
        // cache inválido (não é lista assinada): a embutida inteira
        assertEquals(embutida, OndafoneLista.vigente("{}", agora, 0, chaves))
    }
}
