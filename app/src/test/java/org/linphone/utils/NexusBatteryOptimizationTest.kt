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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusBatteryOptimizationTest {
    @Test
    fun pedeUmaVezSoComKeepAliveEContaSemPush() {
        // keep-alive ligado, conta do QR sem push, bateria otimizada, nunca pedido -> pede
        assertTrue(NexusBatteryOptimization.shouldAsk(true, false, false, 1))
        // keep-alive desligado (usuario desligou) -> nao pede
        assertFalse(NexusBatteryOptimization.shouldAsk(false, false, false, 1))
        // ja isento -> nao pede
        assertFalse(NexusBatteryOptimization.shouldAsk(true, true, false, 1))
        // ja pedido antes (usuario recusou) -> respeita
        assertFalse(NexusBatteryOptimization.shouldAsk(true, false, true, 1))
        // sem conta ou todas com push (Firebase no futuro) -> nao pede
        assertFalse(NexusBatteryOptimization.shouldAsk(true, false, false, 0))
    }
}
