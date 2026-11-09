/*
 * Copyright (c) 2026 Grupo IVM (Nexus / Voipmundo).
 * Based on linphone-android, Copyright (c) 2010-2023 Belledonne Communications SARL.
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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Pedido de isenção da otimização de bateria (P5 fase 1).
 *
 * Sem o Firebase do Grupo IVM a conta do QR do givm não tem push: a chamada recebida com o app
 * fechado depende do serviço em primeiro plano (keep-alive) manter o REGISTER e o socket SIP. Em
 * Doze e nas economias de bateria dos fabricantes (Xiaomi, Samsung...) a rede do app é cortada e a
 * chamada não chega. O pedido abre o diálogo do sistema UMA vez; se o usuário recusar, a escolha
 * dele vale (dá para mudar em Configurações do Android).
 */
object NexusBatteryOptimization {
    const val CONFIG_SECTION = "nexus"
    const val CONFIG_ASKED_KEY = "battery_exemption_asked"

    fun shouldAsk(
        keepAliveEnabled: Boolean,
        ignoringBatteryOptimizations: Boolean,
        alreadyAsked: Boolean,
        accountsWithoutPush: Int
    ): Boolean = keepAliveEnabled && !ignoringBatteryOptimizations && !alreadyAsked && accountsWithoutPush > 0

    /**
     * Pedido do sistema (ainda nao isento) ou a lista de otimizacao de bateria (ja isento, para o usuario
     * desfazer). Usado pelo dialogo do inicio e pela entrada em Configuracoes > Avancado.
     */
    fun requestIntent(context: Context): Intent {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
    }
}
