// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal data class UpstreamSourceRelease(
    val tag: String,
    val title: String,
    val url: String,
)

/** GitLab release metadata only; foreign APKs never replace the DraBornTranslate package. */
internal object GameTranslatorSource {
    const val REFERENCE = "v1.8.51"
    private val endpoint = URI("https://gitlab.com/api/v4/projects/88821849/releases/permalink/latest")
    private val validTag = Regex("""v[0-9]+\.[0-9]+\.[0-9]+""")

    fun parse(metadata: JSONObject): UpstreamSourceRelease {
        val tag = metadata.optString("tag_name")
        require(validTag.matches(tag)) { "GitLab sürüm etiketi doğrulanamadı." }
        return UpstreamSourceRelease(
            tag = tag,
            title = metadata.optString("name").take(140).ifBlank { "GameTranslator " + tag },
            url = "https://gitlab.com/sefflex/GameTranslator/-/releases/" + tag,
        )
    }

    fun latest(): UpstreamSourceRelease {
        val connection = endpoint.toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "DraBornTranslate-SourceChecker")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.contentLengthLong > 131072
            ) throw IllegalStateException("GitLab sürüm bilgisi alınamadı.")
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val body = reader.readText()
                if (body.length > 131072) throw IllegalStateException("GitLab sürüm bilgisi çok büyük.")
                JSONObject(body)
            }
            return parse(json)
        } finally {
            connection.disconnect()
        }
    }
}
