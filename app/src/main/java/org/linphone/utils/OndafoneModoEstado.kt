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

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Estado do modo limitado/completo FORA do linphonerc (revisão 05/11).
 *
 * O linphonerc é escrito por qualquer provisionamento (XML por QR/link de outro provedor, MDM, config-uri), então
 * nada que destrava o modo completo pode morar nele: o último REGISTER confiável de cada conta (`ok_<chave>`), a
 * lista assinada aceita (envelope e versão) e as horas sem registro ficam num armazenamento privado do app
 * (SharedPreferences no Android). `horas_sem_registro` só é aceito quando vem do provisionamento do givm, que o
 * próprio app baixa ([aceitarProvisionamento]); de qualquer outra origem é ignorado.
 */
object OndafoneModoEstado {
    /** Armazenamento privado (SharedPreferences no app; mapa nos testes). */
    interface Armazenamento {
        fun ler(chave: String): String?
        fun gravar(chave: String, valor: String?)
    }

    class Memoria : Armazenamento {
        val mapa = mutableMapOf<String, String>()
        override fun ler(chave: String) = mapa[chave]
        override fun gravar(chave: String, valor: String?) {
            if (valor == null) mapa.remove(chave) else mapa[chave] = valor
        }
    }

    const val MODO = "modo"
    const val LISTA_ENVELOPE = "lista_envelope"
    const val LISTA_VERSAO_ACEITA = "lista_versao_aceita"
    const val LISTA_BAIXADA_MS = "lista_baixada_ms"
    const val HORAS_SEM_REGISTRO = "horas_sem_registro"
    const val OK = "ok_"
    const val TLS_FALHOU = "tls_falhou_"
    const val TCP_FALHOU = "tcp_falhou_"
    // endereços da conta antes / depois de o app trocar o transporte (TLS ou TCP); o nome "tls_" ficou da 1ª versão
    const val TLS_ORIGINAL = "tls_original_"
    const val TLS_APLICADO = "tls_aplicado_"
    // hora (ms) do último REGISTER 200 OK no transporte trocado pelo app: por OndafoneModo.TROCA_OK_DIAS, queda
    // depois disso não é falha do transporte
    const val TROCA_OK = "troca_ok_"
    // hora (ms) da primeira falha de registro no transporte trocado desde o último 200 OK nele
    const val FALHANDO_DESDE = "falhando_desde_"
    // endereços que o usuário escolheu à mão depois de uma troca do app: enquanto forem esses, o app não troca
    const val USUARIO_ESCOLHEU = "usuario_escolheu_"
    val POR_CONTA = listOf(
        OK, TLS_FALHOU, TCP_FALHOU, TLS_ORIGINAL, TLS_APLICADO, TROCA_OK, FALHANDO_DESDE, USUARIO_ESCOLHEU
    )

    /** Seção que versões anteriores usavam no linphonerc: o app não lê mais nada dela e a limpa no start. */
    const val SECAO_LEGADA = "ondafone"

    fun ultimoRegistroConfiavel(estado: Armazenamento, chaveConta: String): Long? =
        estado.ler(OK + chaveConta)?.toLongOrNull()

    fun horas(estado: Armazenamento): Int =
        estado.ler(HORAS_SEM_REGISTRO)?.toIntOrNull()?.let { OndafoneModo.horasSemRegistro(it) }
            ?: OndafoneModo.HORAS_SEM_REGISTRO_PADRAO

    fun versaoAceita(estado: Armazenamento): Long = estado.ler(LISTA_VERSAO_ACEITA)?.toLongOrNull() ?: 0L

    fun esquecerConta(estado: Armazenamento, chaveConta: String) =
        POR_CONTA.forEach { estado.gravar(it + chaveConta, null) }

    /**
     * Um XML de provisionamento acabou de ser aplicado. Só o do givm ([doGivm], baixado pelo próprio app) pode
     * mudar `horas_sem_registro`; nenhum provisionamento grava `ok_*`, lista, versão ou modo.
     */
    fun aceitarProvisionamento(xml: String?, doGivm: Boolean, estado: Armazenamento) {
        if (!doGivm) return
        estado.gravar(HORAS_SEM_REGISTRO, horasDoXml(xml)?.let { OndafoneModo.horasSemRegistro(it).toString() })
    }

    /** `<section name="ondafone"><entry name="horas_sem_registro">N</entry>` do lpconfig em XML, ou null. */
    fun horasDoXml(xml: String?): Int? {
        if (xml.isNullOrBlank()) return null
        // DTD recusado aqui mesmo: o parser do Android não aceita as features de segurança do Xerces (setFeature
        // lança), então elas são só tentativa extra; sem DOCTYPE não há entidade para expandir
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return null
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { f.isExpandEntityReferences = false }
            f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            return null
        }
        val secoes = doc.getElementsByTagNameNS("*", "section")
        for (i in 0 until secoes.length) {
            val secao = secoes.item(i) as Element
            if (secao.getAttribute("name") != SECAO_LEGADA) continue
            val entradas = secao.getElementsByTagNameNS("*", "entry")
            for (j in 0 until entradas.length) {
                val e = entradas.item(j) as Element
                if (e.getAttribute("name") == HORAS_SEM_REGISTRO) return e.textContent.trim().toIntOrNull()
            }
        }
        return null
    }
}
