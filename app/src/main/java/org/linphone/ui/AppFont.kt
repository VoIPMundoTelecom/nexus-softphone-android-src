/*
 * Copyright (c) 2010-2024 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
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

import androidx.annotation.FontRes
import org.linphone.R

enum class AppFont(@FontRes val fontRes: Int) {
    // Light(R.font.inter_light), // 300
    Regular(R.font.inter_regular), // 400
    Medium(R.font.inter_medium), // 500
    // SemiBold(R.font.inter_semi_bold), // 600
    Bold(R.font.inter_bold), // 700
    ExtraBold(R.font.inter_extra_bold) // 800
}
