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
package org.linphone.ui.main.ondafone

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.linphone.R
import org.linphone.core.Erro
import org.linphone.core.OndafoneContaApi
import org.linphone.core.OndafoneSessao
import org.linphone.core.Resultado
import org.linphone.ui.GenericActivity

/**
 * Conta Ondafone (menu lateral): sem sessão abre o Entrar; com sessão mostra o e-mail, "Sair neste aparelho" e
 * "Excluir conta" (exigência do Google Play: a exclusão fica dentro do app, com confirmação).
 */
class OndafoneContaActivity : GenericActivity() {
    companion object {
        private const val ESTADO_ENTRAR_ABERTO = "entrar_aberto"
    }

    private lateinit var api: OndafoneContaApi
    private lateinit var conectado: TextView
    private lateinit var aviso: TextView
    private lateinit var sair: View
    private lateinit var excluir: View
    private var ocupado = false

    /** O Entrar está por cima (sobrevive à recriação): quem decide é o resultado dele. */
    private var entrarAberto = false

    private val entrar = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        entrarAberto = false
        // voltou do Entrar sem entrar: não há o que mostrar aqui
        carregar(fecharSemSessao = true)
    }

    private fun abrirEntrar() {
        entrarAberto = true
        entrar.launch(Intent(this, OndafoneEntrarActivity::class.java))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ESTADO_ENTRAR_ABERTO, entrarAberto)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ondafone_conta_activity)
        api = OndafoneContaApi.criar(this)
        conectado = findViewById(R.id.conectado)
        aviso = findViewById(R.id.aviso)
        sair = findViewById(R.id.sair)
        excluir = findViewById(R.id.excluir)
        findViewById<View>(R.id.voltar).setOnClickListener { finish() }
        sair.setOnClickListener { sair() }
        excluir.setOnClickListener { confirmarExclusao() }
        // recriada com o Entrar por cima: o resultado do Entrar é que decide; senão, carrega (de novo) a sessão
        entrarAberto = savedInstanceState?.getBoolean(ESTADO_ENTRAR_ABERTO) == true
        if (!entrarAberto) carregar(fecharSemSessao = false)
    }


    /** A sessão é lida fora da UI (EncryptedSharedPreferences abre o Keystore). */
    private fun carregar(fecharSemSessao: Boolean) {
        OndafoneSessao.emSegundoPlano({ Pair(api.temSessao, api.email) }) { (sessao, email) ->
            if (isFinishing || isDestroyed) return@emSegundoPlano
            when {
                sessao -> mostrar(email)
                fecharSemSessao -> finish()
                else -> abrirEntrar()
            }
        }
    }

    private fun mostrar(email: String?) {
        conectado.text = if (email != null) {
            getString(R.string.ondafone_conta_conectado_como, email)
        } else {
            getString(R.string.ondafone_conta_conectado)
        }
        habilitar(true)
    }

    private fun habilitar(sim: Boolean) {
        ocupado = !sim
        sair.isEnabled = sim
        excluir.isEnabled = sim
        sair.alpha = if (sim) 1f else 0.5f
        excluir.alpha = if (sim) 1f else 0.5f
    }

    private fun mostrarAviso(t: String?) {
        aviso.text = t.orEmpty()
        aviso.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun mensagemDe(e: Erro): String = e.mensagem ?: getString(
        if (e.status == 0) R.string.ondafone_conta_sem_rede else R.string.ondafone_conta_erro
    )

    private fun sair() {
        if (ocupado) return
        habilitar(false)
        OndafoneSessao.emSegundoPlano({ api.sair() }) { r ->
            if (isFinishing || isDestroyed) return@emSegundoPlano
            // a sessão local já saiu (com ou sem rede, até com 5xx do givm): a tela nunca fica parada no erro
            if (r is Resultado.Falha) Toast.makeText(this, mensagemDe(r.erro), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun confirmarExclusao() {
        if (ocupado) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ondafone_conta_excluir)
            .setMessage(R.string.ondafone_conta_excluir_msg)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.ondafone_conta_excluir_confirmar) { _, _ -> excluir() }
            .show()
    }

    private fun excluir() {
        habilitar(false)
        mostrarAviso(null)
        OndafoneSessao.emSegundoPlano({ api.excluir() }) { r ->
            if (isFinishing || isDestroyed) return@emSegundoPlano
            when (r) {
                is Resultado.Ok -> {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.ondafone_conta_excluida)
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener { finish() }
                        .show()
                }
                is Resultado.Falha -> if (r.erro.status == OndafoneSessao.SEM_SESSAO) {
                    // a sessão deste aparelho caiu (refresh recusado): entrar de novo e então excluir
                    Toast.makeText(this, R.string.ondafone_conta_sessao_expirou, Toast.LENGTH_LONG).show()
                    abrirEntrar()
                } else {
                    habilitar(true)
                    mostrarAviso(mensagemDe(r.erro))
                }
            }
        }
    }
}
