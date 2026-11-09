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

import androidx.annotation.WorkerThread
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.linphone.core.tools.Log

/**
 * Download da lista assinada dos proxies no givm (S3, 03/11; formato e verificação em [OndafoneLista]).
 * HTTPS só para api.givm.com.br, sem seguir redirect, com limite de tamanho. Nada do usuário vai no pedido.
 */
object OndafoneGivm {
    private const val TAG = "[Ondafone givm]"
    private const val HOST = "api.givm.com.br"
    private const val TIMEOUT_MS = 15_000

    private fun abrir(url: String, userAgent: String): HttpsURLConnection? {
        val u = URL(url)
        if (u.protocol != "https" || u.host != HOST || u.port != -1) return null
        val c = u.openConnection() as? HttpsURLConnection ?: return null
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("User-Agent", userAgent)
        return c
    }

    /** Envelope da lista (JSON) ou null; quem chama verifica com [OndafoneLista.verificar]. */
    @WorkerThread
    fun baixarLista(userAgent: String): String? {
        val c = try {
            abrir(OndafoneModo.LISTA_URL, userAgent)
        } catch (e: IOException) {
            Log.w("$TAG list: connection failed: ${e.javaClass.simpleName}")
            null
        } ?: return null
        return try {
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            Log.i("$TAG GET signed list -> HTTP $code")
            val tipo = c.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (code != HttpURLConnection.HTTP_OK || tipo != "application/json") return null
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > OndafoneLista.MAX_BYTES) return null
                    out.write(buffer, 0, n)
                }
            }
            out.toString(Charsets.UTF_8.name())
        } catch (e: IOException) {
            Log.w("$TAG list: request failed: ${e.javaClass.simpleName}")
            null
        } finally {
            c.disconnect()
        }
    }
}
