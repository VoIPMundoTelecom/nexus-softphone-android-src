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
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.linphone.core.tools.Log

/**
 * Baixa o lpconfig do givm no proprio app (revisao 27/10), em vez de entregar a URL ao SDK
 * (core.provisioningUri): o token nao fica gravado em misc/config-uri nem aparece no log do SDK, e o
 * redirect e manual (so segue se o destino tambem for uma URL de provisionamento do givm).
 */
object NexusProvisioningFetcher {
    private const val TAG = "[Nexus Provisioning Fetcher]"

    /** O XML do givm tem 1 conta e 2 codecs (~1,5 KB); acima disso nao e o givm. */
    const val MAX_BYTES = 256 * 1024
    const val MAX_REDIRECTS = 2
    private const val TIMEOUT_MS = 15_000

    /** Prazo total da leitura do corpo: o readTimeout vale por read(), um servidor lento passaria dele. */
    const val READ_DEADLINE_MS = 30_000L

    sealed class Result {
        data class Ok(val xml: String) : Result()
        data class Error(val reason: String) : Result()
    }

    /** Destino de um 3xx, resolvido contra a URL atual; null se nao for uma URL de provisionamento do givm. */
    fun redirectTarget(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val resolved = try {
            URI(current.trim()).resolve(location.trim()).toString()
        } catch (_: Exception) {
            return null
        }
        return if (NexusProvisioning.isGivmProvisioningUrl(resolved)) resolved else null
    }

    /** true se o Content-Type e de XML (o givm responde application/xml; a pagina do navegador e text/html). */
    fun isXmlContentType(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.contains("xml") == true

    /** true se o corpo parece um lpconfig (`<config ...>...</config>`), com ou sem declaracao XML e BOM. */
    fun looksLikeLpconfig(body: String): Boolean {
        var text = body.trim { it == '\uFEFF' || it.isWhitespace() }
        if (text.startsWith("<?xml")) text = text.substringAfter("?>").trim()
        return text.startsWith("<config") && text.endsWith("</config>")
    }

    @WorkerThread
    fun fetch(url: String, userAgent: String): Result {
        var current = url.trim()
        for (hop in 0..MAX_REDIRECTS) {
            if (!NexusProvisioning.isGivmProvisioningUrl(current)) {
                return Result.Error("not a givm provisioning URL")
            }
            val connection = try {
                URL(current).openConnection() as? HttpsURLConnection
            } catch (e: IOException) {
                return Result.Error("connection failed: ${e.javaClass.simpleName}")
            } ?: return Result.Error("not https")
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/xml, text/xml")

                val code = connection.responseCode
                Log.i("$TAG GET [${NexusProvisioning.maskToken(current)}] -> HTTP $code")
                when {
                    code in 300..399 -> {
                        current = redirectTarget(current, connection.getHeaderField("Location"))
                            ?: return Result.Error("redirect to a non-givm URL refused (HTTP $code)")
                    }
                    code != HttpURLConnection.HTTP_OK -> return Result.Error("HTTP $code")
                    !isXmlContentType(connection.contentType) -> return Result.Error("response isn't XML (Content-Type)")
                    else -> {
                        val body = readLimited(connection, System.nanoTime() + READ_DEADLINE_MS * 1_000_000)
                            ?: return Result.Error("response too large or too slow")
                        return if (looksLikeLpconfig(body)) Result.Ok(body) else Result.Error("response isn't an lpconfig XML")
                    }
                }
            } catch (e: IOException) {
                return Result.Error("request failed: ${e.javaClass.simpleName}")
            } finally {
                connection.disconnect()
            }
        }
        return Result.Error("too many redirects")
    }

    private fun readLimited(connection: HttpURLConnection, deadlineNanos: Long): String? {
        val out = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (System.nanoTime() > deadlineNanos) return null
                if (out.size() + read > MAX_BYTES) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
