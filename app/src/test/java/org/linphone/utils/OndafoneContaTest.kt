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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.linphone.core.OndafoneContaMock
import org.linphone.core.Resultado

class OndafoneContaTest {
    @Before
    fun limpar() {
        OndafoneContaMock.reiniciar()
    }

    @Test
    fun `email valido`() {
        assertTrue(OndafoneConta.emailValido("  Maria@Exemplo.com.br "))
        assertEquals("maria@exemplo.com.br", OndafoneConta.normalizarEmail("  Maria@Exemplo.com.br "))
        assertFalse(OndafoneConta.emailValido("maria@exemplo"))
        assertFalse(OndafoneConta.emailValido("maria@@exemplo.com"))
        assertFalse(OndafoneConta.emailValido("maria exemplo@x.com"))
        assertFalse(OndafoneConta.emailValido("@exemplo.com"))
        assertFalse(OndafoneConta.emailValido(""))
        assertFalse(OndafoneConta.emailValido(null))
    }

    @Test
    fun `codigo de 6 digitos`() {
        assertTrue(OndafoneConta.codigoValido("123 456"))
        assertEquals("123456", OndafoneConta.normalizarCodigo("123-456"))
        assertFalse(OndafoneConta.codigoValido("12345"))
        assertFalse(OndafoneConta.codigoValido("1234567"))
    }

    @Test
    fun `cadastro leva o aceite so quando marcado e o marketing separado`() {
        val com = JSONObject(OndafoneConta.corpoCadastro("A@B.com", aceite = true, marketing = false))
        assertEquals("a@b.com", com.getString("email"))
        assertTrue(com.getBoolean("aceiteTermos"))
        assertEquals(OndafoneConta.TERMOS_VERSAO, com.getString("versaoTermos"))
        assertFalse(com.getBoolean("marketing"))
        val sem = JSONObject(OndafoneConta.corpoCadastro("a@b.com", aceite = false, marketing = true))
        assertFalse(sem.has("aceiteTermos"))
        assertTrue(sem.getBoolean("marketing"))
        assertTrue(OndafoneConta.TERMOS_VERSAO.length <= 20)
    }

    @Test
    fun `confirmar manda o aparelho sem dado pessoal`() {
        val o = JSONObject(OndafoneConta.corpoConfirmar("a@b.com", "12 34 56", "6.1.0"))
        assertEquals("123456", o.getString("codigo"))
        val d = o.getJSONObject("dispositivo")
        assertEquals("android", d.getString("plataforma"))
        assertEquals(setOf("plataforma", "versao"), d.keys().asSequence().toSet())
        assertTrue(JSONObject(OndafoneConta.corpoSair(true)).getBoolean("todos"))
    }

    @Test
    fun `aparelhoId da instalacao vai no dispositivo do confirmar, do Google e do renovar`() {
        val id = OndafoneConta.novoAparelhoId()
        assertTrue(OndafoneConta.aparelhoIdValido(id))
        assertNotEquals(id, OndafoneConta.novoAparelhoId())
        val c = JSONObject(OndafoneConta.corpoConfirmar("a@b.com", "123456", "6.3.0", id)).getJSONObject("dispositivo")
        assertEquals(id, c.getString("aparelhoId"))
        assertEquals("android", c.getString("plataforma")) // o givm só aceita android|ios|web
        val g = JSONObject(OndafoneConta.corpoGoogle("eyJ-token", false, false, "6.3.0", id)).getJSONObject("dispositivo")
        assertEquals(id, g.getString("aparelhoId"))
        assertFalse(OndafoneConta.dispositivo("6.3.0", "x".repeat(129)).has("aparelhoId"))
        assertFalse(OndafoneConta.dispositivo(null, null).has("versao"))
    }

    @Test
    fun `mock - entrar, sair e excluir`() {
        assertFalse(OndafoneContaMock.temSessao)
        // como o givm: o código sai sem o aceite, mas a conta nova só nasce com ele (422 termos no confirmar)
        assertTrue(OndafoneContaMock.pedirCodigo("a@b.com", aceite = false, marketing = false) is Resultado.Ok)
        assertEquals(
            "termos",
            (OndafoneContaMock.confirmar("a@b.com", OndafoneContaMock.CODIGO) as Resultado.Falha).erro.motivo
        )
        assertTrue(OndafoneContaMock.pedirCodigo("a@b.com", aceite = true, marketing = false) is Resultado.Ok)
        assertEquals(401, (OndafoneContaMock.confirmar("a@b.com", "000000") as Resultado.Falha).erro.status)
        assertEquals(401, (OndafoneContaMock.confirmar("outro@b.com", OndafoneContaMock.CODIGO) as Resultado.Falha).erro.status)
        assertTrue(OndafoneContaMock.confirmar("A@B.com", OndafoneContaMock.CODIGO) is Resultado.Ok)
        assertTrue(OndafoneContaMock.temSessao)
        OndafoneContaMock.sair()
        assertFalse(OndafoneContaMock.temSessao)
    }

    @Test
    fun `google manda o token, o aparelho e o aceite so quando marcado`() {
        val com = JSONObject(OndafoneConta.corpoGoogle("eyJ-token", aceite = true, marketing = false, versaoApp = "6.3.0"))
        assertEquals("eyJ-token", com.getString("idToken"))
        assertTrue(com.getBoolean("aceiteTermos"))
        assertEquals(OndafoneConta.TERMOS_VERSAO, com.getString("versaoTermos"))
        assertFalse(com.getBoolean("marketing"))
        assertEquals(OndafoneConta.ORIGEM, com.getString("origem"))
        assertEquals("android", com.getJSONObject("dispositivo").getString("plataforma"))
        // o e-mail vem só do token, no givm: o app não manda
        assertFalse(com.has("email"))
        val sem = JSONObject(OndafoneConta.corpoGoogle("eyJ-token", aceite = false, marketing = true, versaoApp = "6.3.0"))
        assertFalse(sem.has("aceiteTermos"))
        assertTrue(sem.getBoolean("marketing"))
    }

    @Test
    fun `mock - google cria conta so com aceite e depois entra sem`() {
        val token = "mock-id-token-do-google-0000"
        assertEquals("termos", (OndafoneContaMock.entrarComGoogle(token, null, aceite = false, marketing = false) as Resultado.Falha).erro.motivo)
        assertTrue(OndafoneContaMock.entrarComGoogle(token, null, aceite = true, marketing = false) is Resultado.Ok)
        assertEquals(OndafoneContaMock.EMAIL_GOOGLE, OndafoneContaMock.email)
        OndafoneContaMock.sair()
        assertFalse(OndafoneContaMock.temSessao)
        // conta já existe: entra sem marcar o aceite de novo
        assertTrue(OndafoneContaMock.entrarComGoogle(token, null, aceite = false, marketing = false) is Resultado.Ok)
        // excluir apaga a conta: entrar de novo é conta nova (pede o aceite)
        OndafoneContaMock.excluir()
        assertFalse(OndafoneContaMock.temSessao)
        assertTrue(OndafoneContaMock.entrarComGoogle(token, null, aceite = false, marketing = false) is Resultado.Falha)
    }

    @Test
    fun `e-mail da conta vem da resposta do givm`() {
        assertEquals("a@b.com", OndafoneConta.emailDaResposta("""{"accessToken":"x","usuario":{"email":"A@B.com"}}"""))
        assertEquals(null, OndafoneConta.emailDaResposta("""{"accessToken":"x"}"""))
        assertEquals(null, OndafoneConta.emailDaResposta("""{"usuario":{"email":null}}"""))
        assertEquals(null, OndafoneConta.emailDaResposta("nao e json"))
    }
}
