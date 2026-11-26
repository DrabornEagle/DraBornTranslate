// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.HttpURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

private class PackHttpError(val status: Int) : IOException("Paket sunucusu HTTP $status döndürdü.")
private class PackSizeError : IOException("Oyun paketi belirtilen boyutu aşıyor.")

internal fun requestPackBytes(
    maxBytes: Int,
    exactSize: Boolean = false,
    waitBeforeRetry: (Long) -> Unit = Thread::sleep,
    connect: () -> HttpURLConnection,
): ByteArray = requestPack(waitBeforeRetry, connect) { input ->
    readLimited(input, maxBytes).also { if (exactSize && it.size != maxBytes) throw EOFException("Incomplete game pack") }
}

internal fun requestPackFile(size: Int, destination: File, connect: () -> HttpURLConnection) {
    requestPack(Thread::sleep, connect) { input ->
        val count = destination.outputStream().use { copyLimited(input, it, size) }
        if (count != size) throw EOFException("Incomplete game pack")
    }
}

private fun <T> requestPack(
    waitBeforeRetry: (Long) -> Unit,
    connect: () -> HttpURLConnection,
    receive: (InputStream) -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            val connection = connect()
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Connection", "close")
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw PackHttpError(code)
                return connection.inputStream.use(receive)
            } finally {
                connection.disconnect()
            }
        } catch (error: IOException) {
            when (error) {
                is PackSizeError -> throw error
                is PackHttpError -> if (error.status != 408 && error.status !in 500..599) throw error
                is SSLHandshakeException, is SSLPeerUnverifiedException ->
                    throw IOException("Paket sunucusuyla güvenli bağlantı kurulamadı.", error)
            }
            if (attempt == 3) {
                throw IOException("Oyun paketi indirilemedi. İnternet bağlantını kontrol edip tekrar dene.", error)
            }
            try {
                waitBeforeRetry(350L * attempt * attempt)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Oyun paketi indirmesi durduruldu.").apply { initCause(interrupted) }
            }
            attempt++
        }
    }
}

internal fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 256 * 1024))
    copyLimited(input, output, maxBytes)
    return output.toByteArray()
}

internal fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Int): Int {
    var total = 0
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return total
        if (count > maxBytes - total) throw PackSizeError()
        output.write(buffer, 0, count)
        total += count
    }
}
