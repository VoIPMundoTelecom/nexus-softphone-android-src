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
package org.linphone.ui

import android.content.Intent
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.utils.DialogUtils
import org.linphone.utils.OndafoneModo
import org.linphone.utils.OndafoneModo.Recurso

/**
 * Tela de oferta do recurso bloqueado no modo limitado: selo "Disponível com a Voipmundo", o que o recurso faz
 * e o link para voipmundo.com.br com utm_campaign=ondafone_upsell_<recurso>. Mostrada pelas Activities
 * (GenericActivity observa coreContext.ondafoneModo.ofertaEvent) ou direto por um Fragment.
 */
object OndafoneOferta {
    private const val TAG = "[Ondafone Oferta]"

    @StringRes
    fun titulo(recurso: Recurso): Int = when (recurso) {
        Recurso.VIDEO -> R.string.ondafone_recurso_video
        Recurso.GRAVACAO -> R.string.ondafone_recurso_gravacao
        Recurso.CONFERENCIA -> R.string.ondafone_recurso_conferencia
        Recurso.TRANSFERENCIA_ASSISTIDA -> R.string.ondafone_recurso_transferencia_assistida
        Recurso.PRESENCA -> R.string.ondafone_recurso_presenca
        Recurso.PUSH -> R.string.ondafone_recurso_push
        Recurso.PROVISIONAMENTO -> R.string.ondafone_recurso_provisionamento
        Recurso.CONTAS_ADICIONAIS -> R.string.ondafone_recurso_contas_adicionais
        Recurso.CODECS -> R.string.ondafone_recurso_codecs
    }

    @StringRes
    fun mensagem(recurso: Recurso): Int = when (recurso) {
        Recurso.VIDEO -> R.string.ondafone_oferta_video
        Recurso.GRAVACAO -> R.string.ondafone_oferta_gravacao
        Recurso.CONFERENCIA -> R.string.ondafone_oferta_conferencia
        Recurso.TRANSFERENCIA_ASSISTIDA -> R.string.ondafone_oferta_transferencia_assistida
        Recurso.PRESENCA -> R.string.ondafone_oferta_presenca
        Recurso.PUSH -> R.string.ondafone_oferta_push
        Recurso.PROVISIONAMENTO -> R.string.ondafone_oferta_provisionamento
        Recurso.CONTAS_ADICIONAIS -> R.string.ondafone_oferta_contas_adicionais
        Recurso.CODECS -> R.string.ondafone_oferta_codecs
    }

    @UiThread
    fun mostrar(activity: AppCompatActivity, recurso: Recurso) {
        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getOndafoneOfertaDialog(
            activity,
            model,
            activity.getString(titulo(recurso)),
            activity.getString(mensagem(recurso)) + "\n\n" + activity.getString(R.string.ondafone_oferta_rodape)
        )
        model.dismissEvent.observe(activity) {
            it.consume { dialog.dismiss() }
        }
        model.confirmEvent.observe(activity) {
            it.consume {
                dialog.dismiss()
                val url = OndafoneModo.urlOferta(recurso)
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (e: Exception) {
                    Log.e("$TAG Can't open offer URL [$url]: $e")
                }
            }
        }
        dialog.show()
    }
}
