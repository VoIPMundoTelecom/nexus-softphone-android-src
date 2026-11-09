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
import org.linphone.utils.OndafoneModo.Conta
import org.linphone.utils.OndafoneModo.Modo

class OndafoneModoEstadoTest {
    private val agora = 1_800_000_000_000L
    private val chave = OndafoneModo.chaveConta("sip:1000@proxy-a.voipmundo.net")!!

    /** XML de provisionamento de outro provedor tentando forjar o modo completo. */
    private val xmlForjado = """
        <?xml version="1.0" encoding="UTF-8"?>
        <config xmlns="http://www.linphone.org/xsds/lpconfig.xsd">
          <section name="ondafone">
            <entry name="ok_$chave">${agora - 60_000}</entry>
            <entry name="modo">COMPLETO</entry>
            <entry name="lista_versao_aceita">999999</entry>
            <entry name="lista_envelope">{"payload_b64":"x","sig_b64":"y"}</entry>
            <entry name="horas_sem_registro">168</entry>
          </section>
        </config>
    """.trimIndent()

    private fun modo(estado: OndafoneModoEstado.Armazenamento, qualifica: Boolean = true) = OndafoneModo.modo(
        listOf(Conta(qualifica, OndafoneModoEstado.ultimoRegistroConfiavel(estado, chave))),
        agora,
        OndafoneModoEstado.horas(estado)
    )

    @Test
    fun `XML com ok_ forjado de outro provedor nao da modo completo`() {
        val estado = OndafoneModoEstado.Memoria()
        OndafoneModoEstado.aceitarProvisionamento(xmlForjado, doGivm = false, estado = estado)
        assertTrue(estado.mapa.isEmpty())
        // mesmo com a conta num host da lista: sem REGISTER 200 OK visto pelo próprio app, fica no limitado
        assertEquals(Modo.LIMITADO, modo(estado))
        assertEquals(OndafoneModo.HORAS_SEM_REGISTRO_PADRAO, OndafoneModoEstado.horas(estado))
        assertEquals(0L, OndafoneModoEstado.versaoAceita(estado))
    }

    @Test
    fun `nem o XML do givm grava ok_ lista ou modo, so as horas`() {
        val estado = OndafoneModoEstado.Memoria()
        OndafoneModoEstado.aceitarProvisionamento(xmlForjado, doGivm = true, estado = estado)
        assertEquals(setOf(OndafoneModoEstado.HORAS_SEM_REGISTRO), estado.mapa.keys)
        assertEquals(168, OndafoneModoEstado.horas(estado))
        assertEquals(Modo.LIMITADO, modo(estado))
    }

    @Test
    fun `horas de outro provedor nao mudam as que o givm mandou`() {
        val estado = OndafoneModoEstado.Memoria()
        OndafoneModoEstado.aceitarProvisionamento(
            """<config><section name="ondafone"><entry name="horas_sem_registro">48</entry></section></config>""",
            doGivm = true,
            estado = estado
        )
        OndafoneModoEstado.aceitarProvisionamento(xmlForjado, doGivm = false, estado = estado)
        assertEquals(48, OndafoneModoEstado.horas(estado))
    }

    @Test
    fun `registro confiavel visto pelo app fica no armazenamento privado`() {
        val estado = OndafoneModoEstado.Memoria()
        estado.gravar(OndafoneModoEstado.OK + chave, (agora - 60_000).toString())
        assertEquals(Modo.COMPLETO, modo(estado))
        assertEquals(Modo.LIMITADO, modo(estado, qualifica = false))
        OndafoneModoEstado.esquecerConta(estado, chave)
        assertEquals(Modo.LIMITADO, modo(estado))
    }

    @Test
    fun `horas do givm tem teto e piso`() {
        fun horas(v: String): Int {
            val estado = OndafoneModoEstado.Memoria()
            OndafoneModoEstado.aceitarProvisionamento(
                """<config><section name="ondafone"><entry name="horas_sem_registro">$v</entry></section></config>""",
                doGivm = true,
                estado = estado
            )
            return OndafoneModoEstado.horas(estado)
        }
        assertEquals(OndafoneModo.HORAS_SEM_REGISTRO_MAX, horas("100000"))
        assertEquals(1, horas("0"))
        assertEquals(OndafoneModo.HORAS_SEM_REGISTRO_PADRAO, horas("abc"))
    }

    @Test
    fun `XML do givm sem a entrada volta ao padrao`() {
        val estado = OndafoneModoEstado.Memoria()
        estado.gravar(OndafoneModoEstado.HORAS_SEM_REGISTRO, "72")
        OndafoneModoEstado.aceitarProvisionamento("<config/>", doGivm = true, estado = estado)
        assertEquals(OndafoneModo.HORAS_SEM_REGISTRO_PADRAO, OndafoneModoEstado.horas(estado))
    }

    @Test
    fun `XML invalido ou com DOCTYPE nao e lido`() {
        assertNull(OndafoneModoEstado.horasDoXml("<config><section"))
        assertNull(
            OndafoneModoEstado.horasDoXml(
                """<?xml version="1.0"?><!doctype c [<!ENTITY h "99">]>""" +
                    """<config><section name="ondafone"><entry name="horas_sem_registro">&h;</entry></section></config>"""
            )
        )
        assertNull(OndafoneModoEstado.horasDoXml(null))
    }

    @Test
    fun `TLS primeiro desligado nao troca transporte`() {
        val lista = OndafoneLista.Lista(1, Long.MAX_VALUE, "t", setOf("proxy-a.voipmundo.net"), setOf("*.voipmundo.net"))
        val udp = OndafoneModo.Salto("proxy-a.voipmundo.net", "udp")
        assertFalse(OndafoneModo.tentarTls(udp, emptyList(), lista, null, agora, habilitado = false))
        assertTrue(OndafoneModo.tentarTls(udp, emptyList(), lista, null, agora, habilitado = true))
    }
}
