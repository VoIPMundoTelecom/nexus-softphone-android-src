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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.utils.OndafoneModo.Conta
import org.linphone.utils.OndafoneModo.Modo
import org.linphone.utils.OndafoneModo.Recurso
import org.linphone.utils.OndafoneModo.Salto

class OndafoneModoTest {
    // hosts fictícios (o fonte público não cita os servidores internos): um por nome, um por curinga de TLS e um
    // por SHA-256, como na lista embutida
    private val lista = OndafoneLista.Lista(
        versao = 1,
        validoAteMs = Long.MAX_VALUE,
        kid = "teste",
        hosts = setOf(
            "proxy-a.voipmundo.net",
            "proxy-b.voipmundo.net",
            "sha256:" + OndafoneLista.hashHost("proxy-c.voipmundo.net")
        ),
        dominiosTls = setOf("*.voipmundo.net")
    )
    private val proxyATls = Salto("proxy-a.voipmundo.net", "tls")
    private val agora = 1_800_000_000_000L
    private val hora = 3_600_000L

    private fun qualifica(
        servidor: Salto,
        rotas: List<Salto> = emptyList(),
        cert: Boolean = true,
        nome: Boolean = true,
        exigeTls: Boolean = true
    ) = OndafoneModo.qualifica(servidor, rotas, cert, nome, lista, exigeTls)

    // --- quem destrava o modo completo

    @Test
    fun `nosso proxy em TLS com certificado verificado qualifica`() {
        assertTrue(qualifica(proxyATls))
        assertTrue(qualifica(Salto("PROXY-B.VoipMundo.net.", "TLS")))
        assertTrue(qualifica(proxyATls, listOf(Salto("proxy-c.voipmundo.net", "tls"))))
    }

    @Test
    fun `provedor generico nunca qualifica`() {
        assertFalse(qualifica(Salto("sip.provedor-teste.com", "tls")))
        assertFalse(qualifica(Salto("sip.provedor-teste.com", "udp")))
    }

    @Test
    fun `trocar o host para um fora da lista nao destrava`() {
        // mesmo domínio nosso, mas fora da allowlist
        assertFalse(qualifica(Salto("proxy-z.voipmundo.net", "tls")))
        // parecido, sufixo de outro domínio
        assertFalse(qualifica(Salto("proxy-a.voipmundo.net.evil.com", "tls")))
        assertFalse(qualifica(Salto("proxy-avoipmundo.net", "tls")))
        // IP do proxy não vale (o certificado é por nome)
        assertFalse(qualifica(Salto("203.0.113.10", "tls")))
        // nome nosso com maddr apontando para outro servidor
        assertFalse(qualifica(Salto("proxy-a.voipmundo.net", "tls", desviado = true)))
        assertFalse(qualifica(proxyATls, listOf(Salto("proxy-c.voipmundo.net", "tls", desviado = true)), exigeTls = false))
        // servidor nosso mas REGISTER sai por uma rota de terceiro
        assertFalse(qualifica(proxyATls, listOf(Salto("proxy.terceiro.com", "tls"))))
    }

    @Test
    fun `sem TLS ou sem verificar o certificado nao qualifica`() {
        assertFalse(qualifica(Salto("proxy-a.voipmundo.net", "udp")))
        assertFalse(qualifica(Salto("proxy-a.voipmundo.net", "tcp")))
        assertFalse(qualifica(Salto("proxy-a.voipmundo.net", null)))
        assertFalse(qualifica(proxyATls, listOf(Salto("proxy-c.voipmundo.net", "udp"))))
        assertFalse(qualifica(proxyATls, cert = false))
        assertFalse(qualifica(proxyATls, nome = false))
    }

    @Test
    fun `host da lista fora dos dominios TLS nao qualifica com TLS exigido`() {
        val l = lista.copy(hosts = lista.hosts + "sip.terceiro.com")
        assertFalse(OndafoneModo.qualifica(Salto("sip.terceiro.com", "tls"), emptyList(), true, true, l, exigeTls = true))
        assertTrue(OndafoneModo.qualifica(Salto("sip.terceiro.com", "udp"), emptyList(), false, false, l, exigeTls = false))
    }

    @Test
    fun `sem exigir TLS basta servidor e rotas na lista`() {
        assertTrue(qualifica(Salto("proxy-a.voipmundo.net", "udp"), cert = false, nome = false, exigeTls = false))
        assertTrue(qualifica(Salto("proxy-c.voipmundo.net", "tcp"), exigeTls = false))
        assertFalse(qualifica(Salto("proxy-z.voipmundo.net", "udp"), exigeTls = false))
        assertFalse(qualifica(Salto("proxy-a.voipmundo.net", "udp"), listOf(Salto("proxy.terceiro.com", "udp")), exigeTls = false))
    }

    @Test
    fun `padrao opcao B - host da lista sem TLS fica completo`() {
        assertFalse(OndafoneModo.MODO_COMPLETO_EXIGE_TLS)
        // valores padrão de qualifica (sem passar exigeTls), em UDP e TCP, sem verificação de certificado
        listOf("udp", "tcp", null).forEach { t ->
            val q = OndafoneModo.qualifica(Salto("proxy-a.voipmundo.net", t), emptyList(), false, false, lista)
            assertTrue("$t", q)
            assertEquals(Modo.COMPLETO, OndafoneModo.modo(listOf(Conta(q, agora - hora)), agora))
        }
    }

    @Test
    fun `padrao opcao B - host fora da lista fica no basico`() {
        listOf(Salto("sip.provedor-teste.com", "udp"), Salto("proxy-z.voipmundo.net", "tls")).forEach { s ->
            val q = OndafoneModo.qualifica(s, emptyList(), true, true, lista)
            assertFalse("$s", q)
            assertEquals(Modo.LIMITADO, OndafoneModo.modo(listOf(Conta(q, agora - hora)), agora))
        }
        // servidor nosso, mas a rota (outbound proxy) é de terceiro
        assertFalse(
            OndafoneModo.qualifica(Salto("proxy-a.voipmundo.net", "udp"), listOf(Salto("sip.terceiro.com", "udp")), true, true, lista)
        )
    }

    // --- TLS primeiro

    @Test
    fun `host em dominiosTls tenta TLS primeiro`() {
        val udp = Salto("proxy-a.voipmundo.net", "udp")
        assertTrue(OndafoneModo.tentarTls(udp, emptyList(), lista, null, agora))
        assertTrue(OndafoneModo.tentarTls(Salto("proxy-b.voipmundo.net", "tcp"), emptyList(), lista, null, agora))
        // rota ainda em UDP também conta
        assertTrue(OndafoneModo.tentarTls(proxyATls, listOf(Salto("proxy-b.voipmundo.net", "udp")), lista, null, agora))
    }

    @Test
    fun `sem dominio TLS ou fora da lista nao tenta TLS`() {
        val semTls = lista.copy(dominiosTls = setOf("*.voipmundo.com.br"))
        assertFalse(OndafoneModo.tentarTls(Salto("proxy-a.voipmundo.net", "udp"), emptyList(), semTls, null, agora))
        assertFalse(OndafoneModo.tentarTls(Salto("sip.provedor-teste.com", "udp"), emptyList(), lista, null, agora))
        assertFalse(OndafoneModo.tentarTls(Salto("proxy-a.voipmundo.net", "udp"), listOf(Salto("sip.terceiro.com", "udp")), lista, null, agora))
    }

    @Test
    fun `nunca o contrario - conta ja em TLS nao muda`() {
        assertFalse(OndafoneModo.tentarTls(proxyATls, emptyList(), lista, null, agora))
        assertFalse(OndafoneModo.tentarTls(Salto("sip.provedor-teste.com", "tls"), emptyList(), lista, null, agora))
    }

    @Test
    fun `TLS que falhou so e tentado de novo depois do intervalo`() {
        val udp = Salto("proxy-a.voipmundo.net", "udp")
        val h = OndafoneModo.TLS_REPETIR_HORAS * hora
        assertFalse(OndafoneModo.tentarTls(udp, emptyList(), lista, agora - hora, agora))
        assertFalse(OndafoneModo.tentarTls(udp, emptyList(), lista, agora - h + 1, agora))
        assertTrue(OndafoneModo.tentarTls(udp, emptyList(), lista, agora - h, agora))
        // marca no futuro (relógio voltou) não trava o TLS para sempre
        assertTrue(OndafoneModo.tentarTls(udp, emptyList(), lista, agora + hora, agora))
    }

    @Test
    fun `so falha de transporte com rede faz voltar ao original`() {
        assertTrue(OndafoneModo.falhaDeTransporte("IOError", true))
        assertTrue(OndafoneModo.falhaDeTransporte("Unknown", true))
        assertTrue(OndafoneModo.falhaDeTransporte(null, true))
        assertFalse(OndafoneModo.falhaDeTransporte("IOError", false))
        assertFalse(OndafoneModo.falhaDeTransporte("Unauthorized", true))
        assertFalse(OndafoneModo.falhaDeTransporte("Forbidden", true))
    }

    // --- SIP por TCP nos servidores Voipmundo (chamada sainte em 408 por UDP fragmentado, 06/11)

    @Test
    fun `servidor Voipmundo em UDP passa para TCP`() {
        assertTrue(OndafoneModo.tentarTcp(Salto("proxy-z.voipmundo.com.br", "udp"), emptyList(), null, agora))
        assertTrue(OndafoneModo.tentarTcp(Salto("proxy-z.voipmundo.net", "Udp"), emptyList(), null, agora))
        // sem transporte na URI = UDP
        assertTrue(OndafoneModo.tentarTcp(Salto("proxy-z.voipmundo.com.br", null), emptyList(), null, agora))
        // rota (outbound proxy) igual ao servidor, como no provisionamento do givm
        val s = Salto("proxy-z.voipmundo.com.br", "udp")
        assertTrue(OndafoneModo.tentarTcp(s, listOf(s), null, agora))
        // não depende da lista assinada nem do modo: a lista embutida nem cita os nomes .com.br
        assertFalse(OndafoneLista.casaAlgum("proxy-z.voipmundo.com.br", OndafoneLista.EMBUTIDA.hosts))
    }

    @Test
    fun `fora dos servidores Voipmundo continua em UDP`() {
        assertFalse(OndafoneModo.tentarTcp(Salto("sip.provedor-teste.com", "udp"), emptyList(), null, agora))
        assertFalse(OndafoneModo.tentarTcp(Salto("voipmundo.com.br", "udp"), emptyList(), null, agora))
        assertFalse(OndafoneModo.tentarTcp(Salto("a.b.voipmundo.com.br", "udp"), emptyList(), null, agora))
        assertFalse(OndafoneModo.tentarTcp(Salto("voipmundo.com.br.golpe.com", "udp"), emptyList(), null, agora))
        assertFalse(OndafoneModo.tentarTcp(Salto("192.0.2.10", "udp"), emptyList(), null, agora))
        // rota de terceiro ou com maddr: não mexe
        val nosso = Salto("proxy-z.voipmundo.com.br", "udp")
        assertFalse(OndafoneModo.tentarTcp(nosso, listOf(Salto("sip.terceiro.com", "udp")), null, agora))
        assertFalse(OndafoneModo.tentarTcp(Salto("proxy-z.voipmundo.com.br", "udp", desviado = true), emptyList(), null, agora))
    }

    @Test
    fun `conta ja em TCP ou TLS nao muda`() {
        assertFalse(OndafoneModo.tentarTcp(Salto("proxy-z.voipmundo.com.br", "tcp"), emptyList(), null, agora))
        assertFalse(OndafoneModo.tentarTcp(Salto("proxy-z.voipmundo.com.br", "tls"), emptyList(), null, agora))
        assertFalse(
            OndafoneModo.tentarTcp(Salto("proxy-z.voipmundo.com.br", "udp"), listOf(Salto("proxy-z.voipmundo.com.br", "tcp")), null, agora)
        )
    }

    @Test
    fun `TCP que falhou so e tentado de novo depois do intervalo e desligado nao troca`() {
        val udp = Salto("proxy-z.voipmundo.com.br", "udp")
        val h = OndafoneModo.TCP_REPETIR_HORAS * hora
        assertFalse(OndafoneModo.tentarTcp(udp, emptyList(), agora - hora, agora))
        assertFalse(OndafoneModo.tentarTcp(udp, emptyList(), agora - h + 1, agora))
        assertTrue(OndafoneModo.tentarTcp(udp, emptyList(), agora - h, agora))
        assertTrue(OndafoneModo.tentarTcp(udp, emptyList(), agora + hora, agora)) // relógio voltou
        assertFalse(OndafoneModo.tentarTcp(udp, emptyList(), null, agora, habilitado = false))
    }

    @Test
    fun `transporte desejado - TLS primeiro, depois TCP`() {
        val udp = Salto("proxy-a.voipmundo.net", "udp")
        fun desejado(tls: Boolean, tlsFalhou: Long? = null, tcp: Boolean = true, tcpFalhou: Long? = null) =
            OndafoneModo.transporteDesejado(udp, emptyList(), lista, tls, tlsFalhou, tcp, tcpFalhou, agora)
        assertEquals(OndafoneModo.Transporte.TLS, desejado(tls = true))
        // TLS desligado (padrão) ou falhou há pouco: TCP
        assertEquals(OndafoneModo.Transporte.TCP, desejado(tls = false))
        assertEquals(OndafoneModo.Transporte.TCP, desejado(tls = true, tlsFalhou = agora - hora))
        // os dois falharam ou desligados: fica no original
        assertNull(desejado(tls = true, tlsFalhou = agora - hora, tcpFalhou = agora - hora))
        assertNull(desejado(tls = false, tcp = false))
        // provedor de terceiro: nada
        assertNull(
            OndafoneModo.transporteDesejado(Salto("sip.provedor-teste.com", "udp"), emptyList(), lista, true, null, true, null, agora)
        )
    }

    // --- modo a partir das contas

    @Test
    fun `sem conta ou so com provedor generico fica limitado`() {
        assertEquals(Modo.LIMITADO, OndafoneModo.modo(emptyList(), agora))
        assertEquals(Modo.LIMITADO, OndafoneModo.modo(listOf(Conta(false, agora)), agora))
    }

    @Test
    fun `conta nossa com registro confiavel recente fica completo`() {
        assertEquals(Modo.COMPLETO, OndafoneModo.modo(listOf(Conta(true, agora - hora)), agora))
        assertEquals(Modo.COMPLETO, OndafoneModo.modo(listOf(Conta(false, null), Conta(true, agora)), agora))
    }

    @Test
    fun `conta nossa ainda sem registro confiavel fica limitado`() {
        assertEquals(Modo.LIMITADO, OndafoneModo.modo(listOf(Conta(true, null)), agora))
    }

    @Test
    fun `registro falhando por N horas volta ao limitado`() {
        val n = OndafoneModo.HORAS_SEM_REGISTRO_PADRAO
        assertEquals(Modo.COMPLETO, OndafoneModo.modo(listOf(Conta(true, agora - (n * hora - 1))), agora))
        assertEquals(Modo.LIMITADO, OndafoneModo.modo(listOf(Conta(true, agora - n * hora)), agora))
        assertEquals(Modo.LIMITADO, OndafoneModo.modo(listOf(Conta(true, agora - 2 * hora)), agora, horas = 2))
    }

    @Test
    fun `relogio voltando para tras nao estende o modo completo`() {
        assertEquals(Modo.LIMITADO, OndafoneModo.modo(listOf(Conta(true, agora + hora)), agora))
    }

    // --- allowlist do givm

    // --- recursos

    @Test
    fun `codecs do modo limitado`() {
        listOf("PCMU", "pcma", "opus", "telephone-event").forEach { assertTrue(it, OndafoneModo.codecPermitido(it)) }
        listOf("G722", "G729", "speex", "GSM", "iLBC", "AMR", null).forEach { assertFalse("$it", OndafoneModo.codecPermitido(it)) }
    }

    @Test
    fun `recursos bloqueados so no limitado`() {
        Recurso.values().forEach {
            assertTrue(OndafoneModo.bloqueado(Modo.LIMITADO, it))
            assertFalse(OndafoneModo.bloqueado(Modo.COMPLETO, it))
        }
    }

    @Test
    fun `uma conta no limitado e QR liberado so sem conta`() {
        assertFalse(OndafoneModo.novaContaBloqueada(Modo.LIMITADO, 0))
        assertTrue(OndafoneModo.novaContaBloqueada(Modo.LIMITADO, 1))
        assertFalse(OndafoneModo.novaContaBloqueada(Modo.COMPLETO, 3))
        assertFalse(OndafoneModo.provisionamentoBloqueado(Modo.LIMITADO, 0))
        assertTrue(OndafoneModo.provisionamentoBloqueado(Modo.LIMITADO, 1))
        assertFalse(OndafoneModo.provisionamentoBloqueado(Modo.COMPLETO, 1))
    }

    @Test
    fun `oferta com UTM por recurso`() {
        assertEquals(
            "https://voipmundo.com.br/?utm_source=ondafone&utm_medium=app&utm_campaign=ondafone_upsell_transferencia_assistida",
            OndafoneModo.urlOferta(Recurso.TRANSFERENCIA_ASSISTIDA)
        )
        val slugs = Recurso.values().map { it.slug }
        assertEquals(slugs.size, slugs.toSet().size)
        assertTrue(slugs.all { it.matches(Regex("[a-z_]+")) })
    }

    @Test
    fun `chave da conta estavel e sem o endereco em claro`() {
        val a = OndafoneModo.chaveConta("sip:1001@proxy-a.voipmundo.net")
        assertEquals(a, OndafoneModo.chaveConta(" SIP:1001@PROXY-A.voipmundo.net "))
        assertNotEquals(a, OndafoneModo.chaveConta("sip:1002@proxy-a.voipmundo.net"))
        assertFalse(a!!.contains("1001"))
        assertNull(OndafoneModo.chaveConta(""))
    }

    @Test
    fun `horas sem registro do lpconfig tem teto`() {
        assertEquals(24, OndafoneModo.horasSemRegistro(24))
        assertEquals(OndafoneModo.HORAS_SEM_REGISTRO_MAX, OndafoneModo.horasSemRegistro(1_000_000))
        assertEquals(1, OndafoneModo.horasSemRegistro(0))
        assertEquals(1, OndafoneModo.horasSemRegistro(-5))
    }

    @Test
    fun `confirmacao do transporte trocado vale TROCA_OK_DIAS desde o ultimo 200 OK`() {
        val dia = 86_400_000L
        assertTrue(OndafoneModo.trocaConfirmada(agora - dia, agora))
        assertTrue(OndafoneModo.trocaConfirmada(agora - (OndafoneModo.TROCA_OK_DIAS * dia - 1), agora))
        assertFalse(OndafoneModo.trocaConfirmada(agora - OndafoneModo.TROCA_OK_DIAS * dia, agora))
        assertFalse(OndafoneModo.trocaConfirmada(null, agora))
        assertFalse(OndafoneModo.trocaConfirmada(1, agora))
        assertTrue(OndafoneModo.trocaConfirmada(agora + dia, agora)) // relógio voltou: não derruba para UDP
        assertFalse(OndafoneModo.trocaConfirmada(agora + 30 * dia, agora)) // data absurda não vale para sempre
    }

    @Test
    fun `falha no transporte trocado so volta ao original sem confirmacao ou com falha persistente`() {
        val dia = 86_400_000L
        val hora = 3_600_000L
        val vencido = agora - (OndafoneModo.TROCA_OK_DIAS + 1) * dia
        assertTrue(OndafoneModo.deveVoltarDoTransporte(null, null, agora)) // nunca registrou no trocado
        assertFalse(OndafoneModo.deveVoltarDoTransporte(agora - dia, agora - 2 * hora, agora)) // queda de conexão
        assertFalse(OndafoneModo.deveVoltarDoTransporte(vencido, agora, agora)) // 1ª falha depois de dias parado
        assertFalse(OndafoneModo.deveVoltarDoTransporte(vencido, agora - hora + 1, agora))
        assertTrue(OndafoneModo.deveVoltarDoTransporte(vencido, agora - hora, agora)) // falha persistente
        assertFalse(OndafoneModo.deveVoltarDoTransporte(vencido, agora + hora, agora)) // relógio voltou
    }
}
