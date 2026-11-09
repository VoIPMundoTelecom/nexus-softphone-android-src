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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import org.linphone.R
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.SignInButton
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import org.linphone.BuildConfig
import org.linphone.core.Erro
import org.linphone.core.OndafoneContaApi
import org.linphone.core.OndafoneSessao
import org.linphone.core.Resultado
import org.linphone.core.tools.Log
import org.linphone.ui.GenericActivity
import org.linphone.utils.OndafoneConta

/**
 * Entrar na conta Ondafone (back #390) por e-mail + código ou com o Google (botão oficial; o givm valida o ID token).
 * O mesmo fluxo cria a conta (com o aceite) e entra numa existente. O consentimento de marketing é separado e começa
 * desmarcado. A sessão fica só no aparelho (cifrada).
 */
class OndafoneEntrarActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Ondafone Entrar]"
        private const val ESTADO_EMAIL_PEDIDO = "email_pedido"
    }

    private lateinit var api: OndafoneContaApi
    private lateinit var email: EditText
    private lateinit var aceite: CheckBox
    private lateinit var marketing: CheckBox
    private lateinit var codigo: EditText
    private lateinit var aviso: TextView
    private lateinit var acao: TextView
    private lateinit var trocar: View
    private lateinit var google: SignInButton
    private var emailPedido: String? = null
    private var ocupado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.ondafone_entrar_activity)
        api = OndafoneContaApi.criar(this)

        email = findViewById(R.id.email)
        aceite = findViewById(R.id.aceite)
        marketing = findViewById(R.id.marketing)
        codigo = findViewById(R.id.codigo)
        aviso = findViewById(R.id.aviso)
        acao = findViewById(R.id.acao)
        trocar = findViewById(R.id.trocar)
        google = findViewById(R.id.google)
        // sem o client ID (web) do Google no build, o botão não aparece; o mock (-PondafoneContaMock=true) simula o login
        if (BuildConfig.ONDAFONE_GOOGLE_WEB_CLIENT_ID.isNotEmpty() || BuildConfig.ONDAFONE_CONTA_MOCK) {
            google.setSize(SignInButton.SIZE_WIDE)
            google.visibility = View.VISIBLE
            findViewById<View>(R.id.ou).visibility = View.VISIBLE
            google.setOnClickListener { entrarComGoogle() }
        }

        findViewById<View>(R.id.voltar).setOnClickListener { finish() }
        findViewById<View>(R.id.privacidade).setOnClickListener { abrirPagina(R.string.website_privacy_policy_url) }
        // termos de uso: o link aparece quando a página estiver publicada (website_terms_and_conditions_url)
        val termos = findViewById<View>(R.id.termos)
        if (getString(R.string.website_terms_and_conditions_url).isNotBlank()) {
            termos.visibility = View.VISIBLE
            termos.setOnClickListener { abrirPagina(R.string.website_terms_and_conditions_url) }
        }
        email.doAfterTextChanged { atualizar() }
        codigo.doAfterTextChanged { atualizar() }
        aceite.setOnCheckedChangeListener { _, _ -> atualizar() }
        acao.setOnClickListener { agir() }
        trocar.setOnClickListener { voltarAoEmail() }
        // girou a tela depois de pedir o código: continua no passo do código
        savedInstanceState?.getString(ESTADO_EMAIL_PEDIDO)?.let { mostrarPassoCodigo(it) }
        atualizar()
    }

    private fun mostrarAviso(t: String?) {
        aviso.text = t.orEmpty()
        aviso.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun abrirPagina(url: Int) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, getString(url).toUri()))
        } catch (e: ActivityNotFoundException) {
            Log.e("$TAG Nada abre a página: $e")
        }
    }

    private fun mensagemDe(e: Erro): String = e.mensagem ?: getString(
        if (e.status == 0) R.string.ondafone_conta_sem_rede else R.string.ondafone_conta_erro
    )

    private fun atualizar() {
        val pedindo = emailPedido == null
        val pode = if (pedindo) {
            OndafoneConta.emailValido(email.text?.toString())
        } else {
            OndafoneConta.codigoValido(codigo.text?.toString())
        }
        acao.isEnabled = pode && !ocupado
        acao.alpha = if (acao.isEnabled) 1f else 0.5f
        google.isEnabled = pedindo && !ocupado
    }

    private fun voltarAoEmail() {
        emailPedido = null
        codigo.setText("")
        codigo.visibility = View.GONE
        trocar.visibility = View.GONE
        email.isEnabled = true
        aceite.isEnabled = true
        marketing.isEnabled = true
        acao.text = getString(R.string.ondafone_conta_pedir_codigo)
        mostrarAviso(null)
        atualizar()
    }

    private fun agir() {
        val pedido = emailPedido
        ocupado = true
        atualizar()
        if (pedido == null) {
            val e = OndafoneConta.normalizarEmail(email.text?.toString())
            val comAceite = aceite.isChecked
            val comMarketing = marketing.isChecked
            OndafoneSessao.emSegundoPlano({ api.pedirCodigo(e, comAceite, comMarketing) }) { r ->
                if (isFinishing || isDestroyed) return@emSegundoPlano
                ocupado = false
                when (r) {
                    is Resultado.Ok -> {
                        mostrarPassoCodigo(e)
                        mostrarAviso(getString(R.string.ondafone_conta_codigo_enviado, e))
                        codigo.requestFocus()
                    }
                    is Resultado.Falha -> mostrarAviso(mensagemDe(r.erro))
                }
                atualizar()
            }
        } else {
            val c = codigo.text?.toString().orEmpty()
            OndafoneSessao.emSegundoPlano({ api.confirmar(pedido, c) }) { r ->
                if (isFinishing || isDestroyed) return@emSegundoPlano
                ocupado = false
                when (r) {
                    is Resultado.Ok -> {
                        setResult(RESULT_OK)
                        finish()
                    }
                    is Resultado.Falha -> if (r.erro.motivo == "termos") {
                        // conta nova sem o aceite: o givm manda o código, mas só cria a conta com o aceite
                        voltarAoEmail()
                        mostrarAviso(mensagemDe(r.erro))
                    } else {
                        mostrarAviso(mensagemDe(r.erro))
                    }
                }
                atualizar()
            }
        }
    }

    private fun mostrarPassoCodigo(e: String) {
        emailPedido = e
        email.isEnabled = false
        aceite.isEnabled = false
        marketing.isEnabled = false
        codigo.visibility = View.VISIBLE
        trocar.visibility = View.VISIBLE
        acao.text = getString(R.string.ondafone_conta_entrar)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ESTADO_EMAIL_PEDIDO, emailPedido)
    }

    // ------------------------------------------------------------------------------------------------ Google

    private fun entrarComGoogle() {
        if (ocupado) return
        ocupado = true
        mostrarAviso(null)
        atualizar()
        val comAceite = aceite.isChecked
        val comMarketing = marketing.isChecked
        if (BuildConfig.ONDAFONE_CONTA_MOCK && BuildConfig.ONDAFONE_GOOGLE_WEB_CLIENT_ID.isEmpty()) {
            enviarTokenGoogle("mock-id-token-do-google-0000", null, comAceite, comMarketing)
            return
        }
        val pedido = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.ONDAFONE_GOOGLE_WEB_CLIENT_ID).build())
            .build()
        lifecycleScope.launch {
            val token = try {
                val r = CredentialManager.create(this@OndafoneEntrarActivity).getCredential(this@OndafoneEntrarActivity, pedido)
                val c = r.credential
                if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    GoogleIdTokenCredential.createFrom(c.data)
                } else {
                    null
                }
            } catch (_: GetCredentialCancellationException) {
                null.also { fimGoogle(null) }
            } catch (e: GetCredentialException) {
                Log.w("$TAG Google sign-in unavailable: ${e.type}")
                null.also { fimGoogle(getString(R.string.ondafone_conta_google_indisponivel)) }
            } catch (e: GoogleIdTokenParsingException) {
                Log.w("$TAG Google ID token not parsed")
                null.also { fimGoogle(getString(R.string.ondafone_conta_google_indisponivel)) }
            }
            if (token != null) enviarTokenGoogle(token.idToken, token.id, comAceite, comMarketing)
            else if (ocupado) fimGoogle(null)
        }
    }

    private fun fimGoogle(aviso: String?) {
        if (isFinishing || isDestroyed) return
        ocupado = false
        mostrarAviso(aviso)
        atualizar()
    }

    private fun enviarTokenGoogle(idToken: String, email: String?, comAceite: Boolean, comMarketing: Boolean) {
        OndafoneSessao.emSegundoPlano({ api.entrarComGoogle(idToken, email, comAceite, comMarketing) }) { r ->
            if (isFinishing || isDestroyed) return@emSegundoPlano
            when (r) {
                is Resultado.Ok -> {
                    setResult(RESULT_OK)
                    finish()
                }
                is Resultado.Falha -> fimGoogle(mensagemDe(r.erro))
            }
        }
    }
}
