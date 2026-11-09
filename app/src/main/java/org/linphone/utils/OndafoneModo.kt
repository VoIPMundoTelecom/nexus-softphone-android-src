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

import java.util.Locale

/**
 * Modos do Ondafone (S3, 03/11): regras puras, sem Android nem SDK, para teste de unidade.
 *
 * - LIMITADO: qualquer provedor SIP ("Usar minha operadora"). Áudio, teclado/DTMF, mudo, viva-voz, espera,
 *   histórico, contatos, chat simples, TLS/SRTP, transferência direta e os codecs G.711/Opus.
 * - COMPLETO: só depois de um REGISTER 200 OK num host da lista assinada do givm ([OndafoneLista]), em UDP, TCP
 *   ou TLS (opção B, 03/11; [MODO_COMPLETO_EXIGE_TLS] = false). Com "TLS primeiro" ligado (BuildConfig.ONDAFONE_TLS_PRIMEIRO,
 *   `-PondafoneTlsPrimeiro=true`; desligado por padrão até os proxies terem 5061 com certificado), host também em `dominiosTls` faz o app tentar TLS primeiro e só cair para o
 *   transporte original (UDP/TCP) se o TLS falhar ([tentarTls]). Volta ao limitado se a
 *   conta sair, mudar de host ou ficar sem registrar por [HORAS_SEM_REGISTRO_PADRAO] horas.
 * - SIP por TCP nos servidores Voipmundo (revisão 06/11, [tentarTcp]): conta em UDP num domínio de
 *   [DOMINIOS_SIP_TCP] passa para TCP, em qualquer modo; se o REGISTER em TCP falhar, volta para UDP.
 *
 * O CoreContext (OndafoneModoManager) junta isto ao SDK: grava o último registro confiável de cada conta e
 * aplica as restrições no Core; a UI mostra o selo "Disponível com a Voipmundo" e a oferta.
 */
object OndafoneModo {
    enum class Modo { LIMITADO, COMPLETO }

    /** Recurso bloqueado no modo limitado; o slug vai no utm_campaign da oferta (ondafone_upsell_<slug>). */
    enum class Recurso(val slug: String) {
        VIDEO("video"),
        GRAVACAO("gravacao"),
        CONFERENCIA("conferencia"),
        TRANSFERENCIA_ASSISTIDA("transferencia_assistida"),
        PRESENCA("presenca"),
        PUSH("push"),
        PROVISIONAMENTO("provisionamento"),
        CONTAS_ADICIONAIS("contas_adicionais"),
        CODECS("codecs")
    }

    /** Codecs de áudio do modo limitado (mime em minúsculas); o telephone-event (DTMF RFC 4733) fica sempre. */
    val CODECS_LIMITADO = setOf("pcmu", "pcma", "opus", "telephone-event")

    /** Sem registro confiável por este tempo, a conta deixa de destravar o modo completo. */
    const val HORAS_SEM_REGISTRO_PADRAO = 24

    /**
     * false (padrão, opção B de 03/11): basta servidor e rotas em `hosts` da lista assinada, em qualquer transporte
     * (UDP/TCP/TLS). true: exige REGISTER em TLS com certificado verificado (cadeia + nome) num domínio de
     * `dominiosTls`.
     */
    const val MODO_COMPLETO_EXIGE_TLS = false

    /** Depois de o TLS falhar numa conta, o app só tenta TLS de novo nela passado este tempo. */
    const val TLS_REPETIR_HORAS = 24

    /** Endpoint da lista assinada no givm (#395): GET, envelope `{"payload_b64", "sig_b64"}` ([OndafoneLista]). */
    const val LISTA_URL = "https://api.givm.com.br/api/ondafone/proxies"

    /** Intervalo entre downloads da lista (a validade de cada lista vem nela, `validoAte`). */
    const val LISTA_ATUALIZAR_HORAS = 6
    const val LISTA_REPETIR_MS = 15 * 60_000L

    private const val OFERTA_BASE = "https://voipmundo.com.br/"

    /** Teto do `horas_sem_registro` vindo do provisionamento do givm (uma semana; ver [OndafoneModoEstado]). */
    const val HORAS_SEM_REGISTRO_MAX = 168

    /** Um salto da sinalização: o servidor da conta ou uma rota (outbound proxy). */
    data class Salto(
        val host: String?,
        val transporte: String?,
        /** A URI tem `maddr` (ou outro desvio do destino): o pacote não vai para [host]. */
        val desviado: Boolean = false
    )

    fun horasSemRegistro(lido: Int): Int = lido.coerceIn(1, HORAS_SEM_REGISTRO_MAX)

    /**
     * true se um REGISTER 200 OK por esta conta prova que ela está num proxy nosso:
     * - servidor e todas as rotas em `hosts` da lista (o REGISTER vai pela rota);
     * - com [exigeTls]: todos em TLS, num domínio de `dominiosTls`, e o SDK verificando o certificado (cadeia) e o
     *   nome do servidor (CN/SAN). Sem as duas verificações, um 200 OK em TLS não prova nada.
     */
    fun qualifica(
        servidor: Salto,
        rotas: List<Salto>,
        verificaCertificado: Boolean,
        verificaNome: Boolean,
        lista: OndafoneLista.Lista,
        exigeTls: Boolean = MODO_COMPLETO_EXIGE_TLS
    ): Boolean {
        if (exigeTls && (!verificaCertificado || !verificaNome)) return false
        return (listOf(servidor) + rotas).all { salto ->
            !salto.desviado &&
                OndafoneLista.casaAlgum(salto.host, lista.hosts) &&
                (
                    !exigeTls || (
                        salto.transporte?.lowercase(Locale.ROOT) == "tls" &&
                            OndafoneLista.casaAlgum(salto.host, lista.dominiosTls)
                        )
                    )
        }
    }

    /** true se todos os saltos (servidor e rotas) estão em `hosts` e em `dominiosTls`: dá para usar TLS. */
    fun aceitaTls(servidor: Salto, rotas: List<Salto>, lista: OndafoneLista.Lista): Boolean =
        (listOf(servidor) + rotas).all {
            OndafoneLista.casaAlgum(it.host, lista.hosts) && OndafoneLista.casaAlgum(it.host, lista.dominiosTls)
        }

    /**
     * true se o app deve trocar a conta para TLS agora: algum salto ainda não está em TLS, todos aceitam TLS
     * ([aceitaTls]) e o TLS não falhou nesta conta nas últimas [TLS_REPETIR_HORAS] horas. Nunca o contrário: uma
     * conta configurada em TLS não é rebaixada; só volta para UDP/TCP a conta que o próprio app passou para TLS.
     */
    fun tentarTls(
        servidor: Salto,
        rotas: List<Salto>,
        lista: OndafoneLista.Lista,
        tlsFalhouMs: Long?,
        agoraMs: Long,
        habilitado: Boolean = true
    ): Boolean {
        if (!habilitado) return false
        val jaTls = (listOf(servidor) + rotas).all { it.transporte?.lowercase(Locale.ROOT) == "tls" }
        if (jaTls || !aceitaTls(servidor, rotas, lista)) return false
        return podeRepetir(tlsFalhouMs, agoraMs, TLS_REPETIR_HORAS)
    }

    /**
     * Domínios dos servidores SIP da Voipmundo onde o app usa SIP por TCP em vez de UDP (revisão 06/11). O INVITE
     * reenviado com Authorization depois do 401 passa de 1472 bytes (o máximo de um datagrama UDP sem fragmentar
     * num MTU de 1500); fragmentado, ele se perde no caminho (rede móvel/NAT) e a chamada sainte terminava em 408,
     * enquanto o REGISTER (menor) e a chamada entrante funcionavam. Em TCP o tamanho não importa (a RFC 3261,
     * 18.1.1, já pede TCP para pedidos acima de 1300 bytes). Curinga de um rótulo, como em [OndafoneLista.casa].
     */
    val DOMINIOS_SIP_TCP = setOf("*.voipmundo.com.br", "*.voipmundo.net")

    /** Depois de o TCP falhar numa conta, o app só tenta TCP de novo nela passado este tempo. */
    const val TCP_REPETIR_HORAS = 24

    /**
     * Quanto tempo vale a confirmação de que o transporte trocado pelo app funciona nesta conta (último REGISTER
     * 200 OK nele). Dentro do prazo, uma falha de registro é queda de conexão e a conta fica no transporte trocado;
     * passado o prazo sem nenhum 200 OK, a próxima falha volta ao original (e o TCP/TLS é tentado de novo depois de
     * [TCP_REPETIR_HORAS]/[TLS_REPETIR_HORAS]).
     */
    const val TROCA_OK_DIAS = 7

    private const val TROCA_OK_MS = TROCA_OK_DIAS * 86_400_000L

    /**
     * Com a confirmação vencida (app parado dias, não só transporte quebrado), uma falha solta não derruba para o
     * original: só volta se as falhas seguem há pelo menos isto desde a primeira depois do último 200 OK.
     */
    const val FALHA_PERSISTENTE_MS = 60 * 60_000L

    /**
     * true se o último 200 OK no transporte trocado ([okMs]) ainda vale. Relógio que voltou conta como válido, mas
     * só até [TROCA_OK_DIAS] à frente (data absurda gravada com o relógio errado não vale para sempre).
     */
    fun trocaConfirmada(okMs: Long?, agoraMs: Long): Boolean =
        okMs != null && okMs > 0 && kotlin.math.abs(agoraMs - okMs) < TROCA_OK_MS

    /**
     * Uma falha de registro no transporte trocado deve voltar a conta ao original? Nunca registrou nele ([okMs]
     * null): sim. Confirmação válida: não (queda de conexão). Vencida: só se as falhas seguem desde
     * [falhandoDesdeMs] há [FALHA_PERSISTENTE_MS] ou mais.
     */
    fun deveVoltarDoTransporte(okMs: Long?, falhandoDesdeMs: Long?, agoraMs: Long): Boolean = when {
        okMs == null -> true
        trocaConfirmada(okMs, agoraMs) -> false
        else -> falhandoDesdeMs != null && falhandoDesdeMs <= agoraMs &&
            agoraMs - falhandoDesdeMs >= FALHA_PERSISTENTE_MS
    }

    private fun podeRepetir(falhouMs: Long?, agoraMs: Long, horas: Int): Boolean =
        falhouMs == null || falhouMs > agoraMs || agoraMs - falhouMs >= horas * 3_600_000L

    /**
     * true se o app deve passar a conta de UDP para TCP agora: servidor e todas as rotas num domínio de
     * [DOMINIOS_SIP_TCP], sem desvio (maddr), todos em UDP, e o TCP não falhou nesta conta nas últimas
     * [TCP_REPETIR_HORAS] horas. Conta já em TCP/TLS (ou com algum salto nesses transportes) fica como está.
     */
    fun tentarTcp(
        servidor: Salto,
        rotas: List<Salto>,
        tcpFalhouMs: Long?,
        agoraMs: Long,
        habilitado: Boolean = true
    ): Boolean {
        if (!habilitado) return false
        val saltos = listOf(servidor) + rotas
        val nossos = saltos.all { !it.desviado && OndafoneLista.casaAlgum(it.host, DOMINIOS_SIP_TCP) }
        val todosUdp = saltos.all { (it.transporte?.lowercase(Locale.ROOT) ?: "udp") == "udp" }
        return nossos && todosUdp && podeRepetir(tcpFalhouMs, agoraMs, TCP_REPETIR_HORAS)
    }

    /** Transporte para o qual o app troca a conta. */
    enum class Transporte { TLS, TCP }

    /**
     * Para onde trocar a conta (null = deixar como está), decidido sobre os saltos ORIGINAIS da conta (os de antes
     * de o app trocar): TLS primeiro ([tentarTls]) e, sem ele, TCP ([tentarTcp]).
     */
    fun transporteDesejado(
        servidor: Salto,
        rotas: List<Salto>,
        lista: OndafoneLista.Lista,
        tlsHabilitado: Boolean,
        tlsFalhouMs: Long?,
        tcpHabilitado: Boolean,
        tcpFalhouMs: Long?,
        agoraMs: Long
    ): Transporte? = when {
        tentarTls(servidor, rotas, lista, tlsFalhouMs, agoraMs, tlsHabilitado) -> Transporte.TLS
        tentarTcp(servidor, rotas, tcpFalhouMs, agoraMs, tcpHabilitado) -> Transporte.TCP
        else -> null
    }

    /**
     * true se um REGISTER que falhou na conta que o app passou para TLS/TCP conta como falha desse transporte (e o
     * app volta para o original). Sem rede não conta (falharia em qualquer transporte); 401/403 é credencial.
     */
    fun falhaDeTransporte(motivo: String?, redeDisponivel: Boolean): Boolean =
        redeDisponivel && motivo?.lowercase(Locale.ROOT) !in setOf("unauthorized", "forbidden")

    /** Estado de uma conta do Core para decidir o modo. */
    data class Conta(val qualifica: Boolean, val ultimoRegistroConfiavelMs: Long?)

    /**
     * COMPLETO se alguma conta ainda qualifica (mesmo host/transporte) e teve um registro confiável nas últimas
     * [horas] horas. Conta removida não aparece aqui; host trocado deixa de qualificar.
     */
    fun modo(contas: List<Conta>, agoraMs: Long, horas: Int = HORAS_SEM_REGISTRO_PADRAO): Modo {
        val limiteMs = horas.coerceAtLeast(1) * 3_600_000L
        val ok = contas.any { c ->
            val ultimo = c.ultimoRegistroConfiavelMs
            c.qualifica && ultimo != null && ultimo <= agoraMs && agoraMs - ultimo < limiteMs
        }
        return if (ok) Modo.COMPLETO else Modo.LIMITADO
    }

    @Suppress("UNUSED_PARAMETER")
    fun bloqueado(modo: Modo, recurso: Recurso): Boolean = modo == Modo.LIMITADO

    /** Codec de áudio permitido no modo limitado (mime do payload, ex.: "PCMU", "opus"). */
    fun codecPermitido(mime: String?): Boolean = mime?.lowercase(Locale.ROOT) in CODECS_LIMITADO

    /**
     * Provisionamento por QR/link: liberado sem conta nenhuma (é como o cliente Voipmundo entra) e no modo
     * completo; com uma conta de outro provedor (limitado) é recurso bloqueado.
     */
    fun provisionamentoBloqueado(modo: Modo, quantidadeDeContas: Int): Boolean =
        modo == Modo.LIMITADO && quantidadeDeContas > 0

    /** Contas adicionais: no limitado, só 1 conta SIP. */
    fun novaContaBloqueada(modo: Modo, quantidadeDeContas: Int): Boolean =
        modo == Modo.LIMITADO && quantidadeDeContas >= 1

    /** Link da oferta, com UTM por recurso. */
    fun urlOferta(recurso: Recurso): String =
        "${OFERTA_BASE}?utm_source=ondafone&utm_medium=app&utm_campaign=ondafone_upsell_${recurso.slug}"

    /** Chave estável de uma conta para guardar o último registro confiável (não guarda o endereço em claro). */
    fun chaveConta(identidade: String?): String? {
        val id = identidade?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return sha.take(12).joinToString("") { "%02x".format(it) }
    }
}
