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
package org.linphone.core

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import kotlin.concurrent.thread
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.tools.Log
import org.linphone.utils.Event
import org.linphone.utils.OndafoneGivm
import org.linphone.utils.OndafoneLista
import org.linphone.utils.OndafoneModo
import org.linphone.utils.OndafoneModoEstado
import org.linphone.utils.OndafoneModo.Modo
import org.linphone.utils.OndafoneModo.Recurso
import org.linphone.utils.OndafoneModo.Transporte

/**
 * Modo limitado/completo do Ondafone no Core (regras em [OndafoneModo]).
 *
 * - Grava, por conta, a hora do último REGISTER 200 OK que qualifica (proxy da lista assinada, UDP/TCP/TLS).
 *   Esse estado, a lista aceita e as horas sem registro ficam em SharedPreferences privadas ([ARQUIVO], fora do
 *   backup), nunca no linphonerc: provisionamento de outro provedor não forja o modo completo ([OndafoneModoEstado]).
 * - TLS primeiro (só com BuildConfig.ONDAFONE_TLS_PRIMEIRO, desligado por padrão): conta num proxy da lista que
 *   também está em `dominiosTls` passa para TLS; se o REGISTER em TLS falhar, volta para o transporte original
 *   (UDP/TCP) e só tenta TLS de novo depois de TLS_REPETIR_HORAS. Desligado, desfaz a troca que tiver feito.
 * - SIP por TCP (BuildConfig.ONDAFONE_SIP_TCP, ligado por padrão): conta em UDP num servidor Voipmundo
 *   (OndafoneModo.DOMINIOS_SIP_TCP) passa para TCP, porque em UDP o INVITE com Authorization se perde
 *   fragmentado e a chamada sainte termina em 408. Se o REGISTER em TCP falhar, volta para UDP e só tenta TCP de
 *   novo depois de TCP_REPETIR_HORAS. Depois do primeiro 200 OK no transporte trocado, falha de registro é tratada
 *   como queda de conexão por TROCA_OK_DIAS (contados do último 200 OK); passado isso, só volta ao original se as
 *   falhas persistirem por FALHA_PERSISTENTE_MS.
 * - Reavalia o modo a cada mudança de registro, conta adicionada/removida, a cada 30 min e na volta ao 1º plano.
 * - No limitado aplica no Core: sem vídeo, sem presença, sem push, só G.711/Opus, sem gravação automática e
 *   keep-alive ligado (recebe com o app em 1º plano/serviço ativo). Guarda antes o que o usuário tinha e
 *   devolve no completo.
 *
 * Tudo no thread do Core, menos [modoAtual]/[quantidadeDeContas] (leitura de qualquer thread) e os LiveData.
 */
class OndafoneModoManager {
    companion object {
        private const val TAG = "[Ondafone Modo]"
        /** SharedPreferences privadas do modo (excluídas do backup em backup_rules/data_extraction_rules). */
        const val ARQUIVO = "ondafone_modo"
        /** Seção onde versões anteriores guardavam o que o usuário tinha antes do limitado (migrada no start). */
        private const val SECAO_ANTES_LEGADA = "ondafone_antes_limitado"
        private const val ANTES = "antes_"
        /** Chaves booleanas guardadas e o padrão de cada uma (o mesmo de restaurar()). */
        private val ANTES_BOOLS = mapOf(
            "video_captura" to true,
            "video_exibicao" to true,
            "video_iniciar" to false,
            "video_aceitar" to true,
            "presenca_assinatura" to true,
            "gravacao_automatica" to false,
            "salvo" to true
        )
        private const val REAVALIAR_MS = 30 * 60 * 1000L
    }

    /** Modo atual para a UI (observar no thread principal). */
    val modo = MutableLiveData<Modo>()

    /** Quantidade de contas SIP (regra de 1 conta no limitado / QR só sem conta). */
    val contas = MutableLiveData<Int>()

    /** true no modo limitado: os layouts mostram o selo "Disponível com a Voipmundo". */
    val limitado = MutableLiveData<Boolean>()

    /** true se adicionar conta (ou provisionar por QR/link) está bloqueado agora. */
    val novaContaBloqueada = MutableLiveData<Boolean>()

    /** Recurso bloqueado tocado em algum lugar: as Activities mostram a oferta. */
    val ofertaEvent = MutableLiveData<Event<Recurso>>()

    @Volatile
    var modoAtual: Modo = Modo.LIMITADO
        private set

    @Volatile
    var quantidadeDeContas: Int = 0
        private set

    /** Estado do modo fora do linphonerc. Lido e escrito só no thread do Core. */
    private val prefs by lazy { coreContext.context.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE) }

    private val estado: OndafoneModoEstado.Armazenamento by lazy {
        object : OndafoneModoEstado.Armazenamento {
            override fun ler(chave: String): String? = prefs.getString(chave, null)
            override fun gravar(chave: String, valor: String?) {
                prefs.edit().apply { if (valor == null) remove(chave) else putString(chave, valor) }.apply()
            }
        }
    }

    private var iniciado = false
    private var geracao = 0 // o Core reinicia no provisionamento: só um ciclo de reavaliação por início

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (state == RegistrationState.Ok) {
                val chave = chave(account)
                when (account.params.serverAddress?.transport) {
                    TransportType.Tls -> chave?.let { estado.gravar(OndafoneModoEstado.TLS_FALHOU + it, null) }
                    TransportType.Tcp -> chave?.let { estado.gravar(OndafoneModoEstado.TCP_FALHOU + it, null) }
                    else -> Unit
                }
                if (chave != null && estado.ler(OndafoneModoEstado.TLS_APLICADO + chave) == enderecos(account.params)) {
                    estado.gravar(OndafoneModoEstado.TROCA_OK + chave, System.currentTimeMillis().toString())
                    estado.gravar(OndafoneModoEstado.FALHANDO_DESDE + chave, null)
                }
                if (chave != null && qualifica(core, account)) {
                    estado.gravar(OndafoneModoEstado.OK + chave, System.currentTimeMillis().toString())
                    Log.i("$TAG Trusted REGISTER 200 OK on a listed proxy over [${account.params.transport}]")
                }
            } else if (state == RegistrationState.Failed) {
                voltarDoTransporte(core, account)
            }
            avaliar(core)
        }

        @WorkerThread
        override fun onAccountAdded(core: Core, account: Account) {
            prepararTransportes(core)
            avaliar(core)
        }

        @WorkerThread
        override fun onAccountRemoved(core: Core, account: Account) {
            chave(account)?.let { OndafoneModoEstado.esquecerConta(estado, it) }
            avaliar(core)
        }
    }

    /** true = recurso bloqueado agora (qualquer thread). */
    @AnyThread
    fun bloqueado(recurso: Recurso): Boolean = when (recurso) {
        Recurso.PROVISIONAMENTO -> OndafoneModo.provisionamentoBloqueado(modoAtual, quantidadeDeContas)
        Recurso.CONTAS_ADICIONAIS -> OndafoneModo.novaContaBloqueada(modoAtual, quantidadeDeContas)
        else -> OndafoneModo.bloqueado(modoAtual, recurso)
    }

    /** Se bloqueado, pede a oferta e devolve true (quem chamou não segue com a ação). */
    @AnyThread
    fun bloquearComOferta(recurso: Recurso): Boolean {
        if (!bloqueado(recurso)) return false
        Log.i("$TAG [${recurso.slug}] is blocked in limited mode, showing offer")
        ofertaEvent.postValue(Event(recurso))
        return true
    }

    @WorkerThread
    fun onCoreStarted(core: Core) {
        if (iniciado) return
        iniciado = true
        geracao++
        // versões anteriores guardavam o estado na seção [ondafone] do linphonerc; nada dali é confiável
        core.config.cleanSection(OndafoneModoEstado.SECAO_LEGADA)
        migrarAntesDoLinphonerc(core.config)
        // começa no modo salvo (evita piscar o selo antes do 1º registro); avaliar() corrige já em seguida
        modoAtual = if (estado.ler(OndafoneModoEstado.MODO) == Modo.COMPLETO.name) Modo.COMPLETO else Modo.LIMITADO
        core.addListener(coreListener)
        prepararTransportes(core)
        avaliar(core, aplicarSempre = true)
        atualizarListaSeVencida(core)
        agendarReavaliacao()
    }

    @WorkerThread
    fun onCoreStopped(core: Core) {
        core.removeListener(coreListener)
        iniciado = false
    }

    @WorkerThread
    fun onForeground(core: Core) {
        if (!iniciado) return
        avaliar(core)
        atualizarListaSeVencida(core)
    }

    /**
     * Um XML de provisionamento foi aplicado. [doGivm] = baixado pelo próprio app do givm (só ele pode mudar
     * `horas_sem_registro`); qualquer outro é ignorado aqui.
     */
    @WorkerThread
    fun provisionamentoAplicado(xml: String?, doGivm: Boolean) {
        OndafoneModoEstado.aceitarProvisionamento(xml, doGivm, estado)
    }

    private fun chave(account: Account): String? =
        OndafoneModo.chaveConta(account.params.identityAddress?.asStringUriOnly())

    // maddr manda o pacote para outro destino que não o nome da URI: não prova que o REGISTER chegou num proxy nosso
    private fun salto(address: Address?) = OndafoneModo.Salto(
        address?.domain,
        address?.transport?.name,
        desviado = address?.hasUriParam("maddr") == true
    )

    /**
     * Lista vigente: a do givm no cache, verificada de novo a cada leitura (assinatura, validade e versão), ou a
     * embutida.
     */
    @WorkerThread
    private fun listaVigente(): OndafoneLista.Lista {
        val envelope = estado.ler(OndafoneModoEstado.LISTA_ENVELOPE) ?: return OndafoneLista.EMBUTIDA
        return OndafoneLista.vigente(envelope, System.currentTimeMillis(), OndafoneModoEstado.versaoAceita(estado))
    }

    private fun enderecos(params: AccountParams): String =
        // URIs SIP não têm espaço: um por item, separados por espaço
        (listOf(params.serverAddress) + params.routesAddresses).joinToString(" ") { it?.asStringUriOnly().orEmpty() }

    private fun paraTransporte(address: Address, alvo: Transporte): Address {
        val c = address.clone()
        if (alvo == Transporte.TLS) {
            if (c.port == 5060) c.port = 0 // porta SIP padrão do UDP/TCP: em TLS vale a do TLS (5061/SRV)
            c.transport = TransportType.Tls
        } else {
            c.transport = TransportType.Tcp // mesma porta: UDP e TCP dividem a 5060
        }
        return c
    }

    private fun transporteDe(address: Address?): Transporte? = when (address?.transport) {
        TransportType.Tls -> Transporte.TLS
        TransportType.Tcp -> Transporte.TCP
        else -> null
    }

    /** Endereços guardados por [enderecos] de volta em servidor + rotas; null se não der para ler. */
    private fun lerEnderecos(texto: String?): Pair<Address, List<Address>>? {
        val itens = texto?.split(" ")?.filter { it.isNotEmpty() } ?: return null
        val servidor = Factory.instance().createAddress(itens.firstOrNull() ?: return null) ?: return null
        return servidor to itens.drop(1).mapNotNull { Factory.instance().createAddress(it) }
    }

    /**
     * Escolhe o transporte de cada conta ([OndafoneModo.transporteDesejado], sobre os endereços de antes da troca):
     * TLS primeiro (com a flag e o host em `dominiosTls`) e, sem ele, TCP nos servidores Voipmundo. Desfaz a troca
     * que deixou de valer (flag desligada, host fora da lista, falha recente) e não mexe em conta que o usuário
     * editou depois da troca.
     */
    @WorkerThread
    private fun prepararTransportes(core: Core, lista: OndafoneLista.Lista = listaVigente()) {
        val agora = System.currentTimeMillis()
        for (account in core.accountList) {
            val chave = chave(account) ?: continue
            val params = account.params
            val servidorAtual = params.serverAddress ?: continue
            val aplicado = estado.ler(OndafoneModoEstado.TLS_APLICADO + chave)
            if (aplicado != null && aplicado != enderecos(params)) {
                // o usuário mudou a conta depois da troca: guarda a escolha dele e não mexe enquanto ela valer
                limparTls(chave)
                estado.gravar(OndafoneModoEstado.USUARIO_ESCOLHEU + chave, enderecos(params))
                continue
            }
            if (aplicado == null && estado.ler(OndafoneModoEstado.USUARIO_ESCOLHEU + chave) == enderecos(params)) continue
            val original = if (aplicado != null) {
                lerEnderecos(estado.ler(OndafoneModoEstado.TLS_ORIGINAL + chave))
            } else {
                servidorAtual to params.routesAddresses.toList()
            }
            if (original == null) {
                limparTls(chave) // original ilegível: fica como está
                continue
            }
            val (servidor, rotas) = original
            val desejado = OndafoneModo.transporteDesejado(
                servidor = salto(servidor),
                rotas = rotas.map { salto(it) },
                lista = lista,
                tlsHabilitado = BuildConfig.ONDAFONE_TLS_PRIMEIRO,
                tlsFalhouMs = estado.ler(OndafoneModoEstado.TLS_FALHOU + chave)?.toLongOrNull(),
                tcpHabilitado = BuildConfig.ONDAFONE_SIP_TCP,
                tcpFalhouMs = estado.ler(OndafoneModoEstado.TCP_FALHOU + chave)?.toLongOrNull(),
                agoraMs = agora
            )
            if (aplicado != null) {
                if (desejado != null && desejado == transporteDe(servidorAtual)) continue // já está como deve
                restaurarOriginal(account, chave)
            }
            if (desejado == null) continue
            val clone = account.params.clone()
            clone.serverAddress = paraTransporte(servidor, desejado)
            clone.setRoutesAddresses(rotas.map { paraTransporte(it, desejado) }.toTypedArray())
            Log.i("$TAG Switching account from [${account.params.transport}] to [$desejado]")
            estado.gravar(OndafoneModoEstado.TLS_ORIGINAL + chave, enderecos(account.params))
            estado.gravar(OndafoneModoEstado.TLS_APLICADO + chave, enderecos(clone))
            account.params = clone
        }
    }

    /**
     * REGISTER falhou: se foi o app que trocou o transporte (TLS/TCP) e a falha é desse transporte, volta ao
     * original e escolhe de novo (TLS que falhou ainda pode ir para TCP).
     */
    @WorkerThread
    private fun voltarDoTransporte(core: Core, account: Account) {
        val chave = chave(account) ?: return
        val aplicado = estado.ler(OndafoneModoEstado.TLS_APLICADO + chave) ?: return
        if (aplicado != enderecos(account.params)) {
            limparTls(chave)
            estado.gravar(OndafoneModoEstado.USUARIO_ESCOLHEU + chave, enderecos(account.params))
            return
        }
        if (!OndafoneModo.falhaDeTransporte(account.error.name, core.isNetworkReachable)) return
        // registrou neste transporte nos últimos TROCA_OK_DIAS: queda de conexão (troca de rede, NAT, servidor
        // reiniciando) não é motivo para voltar ao UDP, onde a chamada sainte dá 408; o SDK reconecta sozinho.
        // Vencida (app parado ou transporte quebrado há dias), só volta se as falhas persistirem.
        val agora = System.currentTimeMillis()
        val bruto = estado.ler(OndafoneModoEstado.TROCA_OK + chave)
        if (bruto == "1") {
            // valor da versão anterior (sem hora): a troca já tinha funcionado, ganha um prazo novo
            estado.gravar(OndafoneModoEstado.TROCA_OK + chave, agora.toString())
            return
        }
        val okMs = bruto?.toLongOrNull()
        var desde = estado.ler(OndafoneModoEstado.FALHANDO_DESDE + chave)?.toLongOrNull()
        if (okMs != null && desde == null) {
            desde = agora
            estado.gravar(OndafoneModoEstado.FALHANDO_DESDE + chave, agora.toString())
        }
        if (!OndafoneModo.deveVoltarDoTransporte(okMs, desde, agora)) return
        val transporte = transporteDe(account.params.serverAddress) ?: return
        val chaveFalha = if (transporte == Transporte.TLS) OndafoneModoEstado.TLS_FALHOU else OndafoneModoEstado.TCP_FALHOU
        estado.gravar(chaveFalha + chave, System.currentTimeMillis().toString())
        Log.w("$TAG REGISTER over [$transporte] failed [${account.error}], falling back to the original transport")
        restaurarOriginal(account, chave)
        prepararTransportes(core)
    }

    @WorkerThread
    private fun restaurarOriginal(account: Account, chave: String) {
        val original = lerEnderecos(estado.ler(OndafoneModoEstado.TLS_ORIGINAL + chave))
        limparTls(chave)
        val (servidor, rotas) = original ?: return
        val clone = account.params.clone()
        clone.serverAddress = servidor
        clone.setRoutesAddresses(rotas.toTypedArray())
        account.params = clone
    }

    private fun limparTls(chave: String) {
        estado.gravar(OndafoneModoEstado.TLS_ORIGINAL + chave, null)
        estado.gravar(OndafoneModoEstado.TLS_APLICADO + chave, null)
        estado.gravar(OndafoneModoEstado.TROCA_OK + chave, null)
        estado.gravar(OndafoneModoEstado.FALHANDO_DESDE + chave, null)
    }

    @WorkerThread
    private fun qualifica(core: Core, account: Account, lista: OndafoneLista.Lista = listaVigente()): Boolean {
        val params = account.params
        return OndafoneModo.qualifica(
            servidor = salto(params.serverAddress),
            rotas = params.routesAddresses.map { salto(it) },
            verificaCertificado = core.config.getInt("sip", "verify_server_certs", 1) == 1,
            verificaNome = core.config.getInt("sip", "verify_server_cn", 1) == 1,
            lista = lista
        )
    }

    @WorkerThread
    fun avaliar(core: Core, aplicarSempre: Boolean = false) {
        val agora = System.currentTimeMillis()
        val lista = listaVigente()
        val contasAgora = core.accountList.map { account ->
            val ok = chave(account)?.let { OndafoneModoEstado.ultimoRegistroConfiavel(estado, it) }
            OndafoneModo.Conta(qualifica(core, account, lista), ok)
        }
        val novo = OndafoneModo.modo(contasAgora, agora, OndafoneModoEstado.horas(estado))
        quantidadeDeContas = contasAgora.size
        contas.postValue(contasAgora.size)
        if (novo != modoAtual || aplicarSempre) {
            if (novo != modoAtual) Log.w("$TAG Mode changed from [$modoAtual] to [$novo]")
            modoAtual = novo
            estado.gravar(OndafoneModoEstado.MODO, novo.name)
            aplicar(core, novo)
        }
        modo.postValue(novo)
        limitado.postValue(novo == Modo.LIMITADO)
        novaContaBloqueada.postValue(OndafoneModo.novaContaBloqueada(novo, contasAgora.size))
    }

    @WorkerThread
    private fun aplicar(core: Core, modo: Modo) {
        if (modo == Modo.LIMITADO) limitar(core) else restaurar(core)
    }

    private fun antesBool(k: String, padrao: Boolean): Boolean =
        estado.ler(ANTES + k)?.toBooleanStrictOrNull() ?: padrao

    private fun gravarAntes(k: String, v: Any?) = estado.gravar(ANTES + k, v?.toString())

    private fun limparAntes() {
        ANTES_BOOLS.keys.forEach { gravarAntes(it, null) }
        gravarAntes("codecs", null)
    }

    /**
     * O que o usuário tinha antes do limitado (vídeo, presença, gravação, codecs) ia para o linphonerc, que qualquer
     * provisionamento reescreve: passa para as prefs privadas uma vez e limpa a seção.
     */
    @WorkerThread
    private fun migrarAntesDoLinphonerc(cfg: Config) {
        if (cfg.getBool(SECAO_ANTES_LEGADA, "salvo", false) && !antesBool("salvo", false)) {
            // um commit() só, no disco antes de limpar o linphonerc: um kill no meio não perde o que o usuário tinha
            val editor = prefs.edit()
            ANTES_BOOLS.forEach { (k, padrao) ->
                editor.putString(ANTES + k, cfg.getBool(SECAO_ANTES_LEGADA, k, padrao).toString())
            }
            cfg.getString(SECAO_ANTES_LEGADA, "codecs", null)?.let { editor.putString(ANTES + "codecs", it) }
            if (!editor.commit()) {
                Log.w("$TAG Could not save user settings from linphonerc, keeping the legacy section")
                return
            }
            Log.i("$TAG User settings saved before limited mode moved out of linphonerc")
        }
        cfg.cleanSection(SECAO_ANTES_LEGADA)
    }

    @WorkerThread
    private fun limitar(core: Core) {
        if (!antesBool("salvo", false)) {
            val policy = core.videoActivationPolicy
            gravarAntes("video_captura", core.isVideoCaptureEnabled)
            gravarAntes("video_exibicao", core.isVideoDisplayEnabled)
            gravarAntes("video_iniciar", policy.automaticallyInitiate)
            gravarAntes("video_aceitar", policy.automaticallyAccept)
            gravarAntes("presenca_assinatura", core.isFriendListSubscriptionEnabled)
            gravarAntes("gravacao_automatica", corePreferences.automaticallyStartCallRecording)
            gravarAntes(
                "codecs",
                core.audioPayloadTypes.filter { it.enabled() }.joinToString(",") { "${it.mimeType}/${it.clockRate}" }
            )
            gravarAntes("salvo", true)
        }

        // vídeo
        core.isVideoCaptureEnabled = false
        core.isVideoDisplayEnabled = false
        val policy = core.videoActivationPolicy.clone()
        policy.automaticallyInitiate = false
        policy.automaticallyAccept = false
        core.videoActivationPolicy = policy
        // presença/BLF
        core.isFriendListSubscriptionEnabled = false
        // gravação
        corePreferences.automaticallyStartCallRecording = false
        // codecs: só G.711 e Opus (+ telephone-event)
        for (pt in core.audioPayloadTypes) {
            val permitido = OndafoneModo.codecPermitido(pt.mimeType)
            if (pt.enabled() != permitido) pt.enable(permitido)
        }
        // push e publicação de presença nas contas
        for (account in core.accountList) {
            val params = account.params
            if (params.pushNotificationAllowed || params.remotePushNotificationAllowed || params.isPublishEnabled) {
                val clone = params.clone()
                clone.pushNotificationAllowed = false
                clone.remotePushNotificationAllowed = false
                clone.isPublishEnabled = false
                account.params = clone
            }
        }
        // sem push: recebe só com o app em 1º plano ou o serviço ativo
        if (!corePreferences.keepServiceAlive) {
            Log.w("$TAG Limited mode: enabling keep-alive foreground service (no push)")
            corePreferences.keepServiceAlive = true
            coreContext.startKeepAliveService()
        }
        Log.i("$TAG Limited mode restrictions applied")
    }

    @WorkerThread
    private fun restaurar(core: Core) {
        if (!antesBool("salvo", false)) return
        core.isVideoCaptureEnabled = antesBool("video_captura", true)
        core.isVideoDisplayEnabled = antesBool("video_exibicao", true)
        val policy = core.videoActivationPolicy.clone()
        policy.automaticallyInitiate = antesBool("video_iniciar", false)
        policy.automaticallyAccept = antesBool("video_aceitar", true)
        core.videoActivationPolicy = policy
        core.isFriendListSubscriptionEnabled = antesBool("presenca_assinatura", true)
        corePreferences.automaticallyStartCallRecording = antesBool("gravacao_automatica", false)
        val codecs = estado.ler(ANTES + "codecs")?.split(",")?.toSet()
        if (codecs != null) {
            for (pt in core.audioPayloadTypes) {
                val habilitado = "${pt.mimeType}/${pt.clockRate}" in codecs
                if (pt.enabled() != habilitado) pt.enable(habilitado)
            }
        }
        // push: o Ondafone ainda não tem FCM; a presença publicada vem do lpconfig do givm (publish=0)
        limparAntes()
        Log.i("$TAG Complete mode: user settings restored")
    }

    @WorkerThread
    private fun atualizarListaSeVencida(core: Core) {
        val baixadaMs = estado.ler(OndafoneModoEstado.LISTA_BAIXADA_MS)?.toLongOrNull() ?: 0L
        val agora = System.currentTimeMillis()
        if (baixadaMs in (agora - OndafoneModo.LISTA_ATUALIZAR_HORAS * 3_600_000L)..agora) return
        estado.gravar(OndafoneModoEstado.LISTA_BAIXADA_MS, agora.toString())
        thread(name = "OndafoneLista") {
            val envelope = OndafoneGivm.baixarLista(userAgent())
            coreContext.postOnCoreThread { c ->
                val versao = OndafoneModoEstado.versaoAceita(estado)
                when (val r = OndafoneLista.verificar(envelope, System.currentTimeMillis(), versao)) {
                    is OndafoneLista.Resultado.Ok -> {
                        estado.gravar(OndafoneModoEstado.LISTA_ENVELOPE, envelope)
                        estado.gravar(OndafoneModoEstado.LISTA_VERSAO_ACEITA, r.lista.versao.toString())
                        Log.i("$TAG Signed list v${r.lista.versao} (kid ${r.lista.kid}) accepted: [${r.lista.hosts.size}] host(s)")
                        prepararTransportes(c, r.lista)
                        avaliar(c)
                    }
                    is OndafoneLista.Resultado.Rejeitada -> {
                        // download falhou (rede): tenta de novo em 15 min, não em 6 h; lista recusada espera as 6 h
                        if (envelope == null) {
                            val proxima = System.currentTimeMillis() -
                                OndafoneModo.LISTA_ATUALIZAR_HORAS * 3_600_000L + OndafoneModo.LISTA_REPETIR_MS
                            estado.gravar(OndafoneModoEstado.LISTA_BAIXADA_MS, proxima.toString())
                        }
                        Log.w("$TAG Signed list from givm refused (${r.motivo}), keeping the last valid one")
                    }
                }
            }
        }
    }

    private fun agendarReavaliacao() {
        val g = geracao
        coreContext.postOnCoreThreadDelayed({ core ->
            if (!iniciado || g != geracao) return@postOnCoreThreadDelayed
            avaliar(core)
            agendarReavaliacao()
        }, REAVALIAR_MS)
    }

    private fun userAgent() = "nexus-app/${BuildConfig.VERSION_NAME} (Android)"
}
