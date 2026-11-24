// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubUpdateTest {
    private val digest = "a".repeat(64)
    private val apk = "DraBornTranslate-v0.4.10-Android-arm64.apk"
    private val download = "https://github.com/DrabornEagle/DraBornTranslate/releases/download/v0.4.10/$apk"

    private fun release(url: String = download, assetDigest: String = "sha256:$digest", assetName: String = apk) =
        JSONObject().put("tag_name", "v0.4.10").put("assets", JSONArray().put(
            JSONObject().put("name", assetName).put("state", "uploaded").put("size", 111_821_150)
                .put("browser_download_url", url).put("digest", assetDigest),
        ))

    @Test
    fun selectsTheUploadedArm64ApkAndComparesNumericVersions() {
        val result = checkNotNull(GitHubUpdate.parse(release(), "0.4.9"))
        assertEquals("0.4.10", result.version)
        assertEquals(download, result.url.toString())
        assertEquals(111_821_150L, result.size)
        assertEquals(digest, result.sha256)
        assertNull(GitHubUpdate.parse(release(), "0.4.10"))
        assertNull(GitHubUpdate.parse(release(), "0.5.0"))
    }

    @Test
    fun acceptsTheV5InitialTwoPartTag() {
        val name = "DraBornTranslate-v5.0-Android-arm64.apk"
        val url = "https://github.com/DrabornEagle/DraBornTranslate/releases/download/v5.0/$name"
        val initial = release(url = url, assetName = name).put("tag_name", "v5.0")
        assertEquals("5.0", GitHubUpdate.parse(initial, "0.4.11")?.version)
        assertNull(GitHubUpdate.parse(initial, "5.0.0"))
    }

    @Test
    fun skipsDraftsAndPrereleases() {
        assertNull(GitHubUpdate.parse(release().put("draft", true), "0.4.9"))
        assertNull(GitHubUpdate.parse(release().put("prerelease", true), "0.4.9"))
    }

    @Test
    fun rejectsMissingDigestsAndDownloadsFromOtherLocations() {
        assertThrows(UpdateException::class.java) {
            GitHubUpdate.parse(release(assetDigest = ""), "0.4.9")
        }
        assertThrows(UpdateException::class.java) {
            GitHubUpdate.parse(release(url = download.replace("https:", "http:")), "0.4.9")
        }
        assertThrows(UpdateException::class.java) {
            GitHubUpdate.parse(release(url = download.replace("DrabornEagle", "another-account")), "0.4.9")
        }
    }

    @Test
    fun displaysAnErrorWhenTheCompatibleApkIsMissing() {
        assertThrows(UpdateException::class.java) {
            GitHubUpdate.parse(release(assetName = "$apk.sha256"), "0.4.9")
        }
        release().apply { getJSONArray("assets").getJSONObject(0).put("state", "new") }.let { pending ->
            assertThrows(UpdateException::class.java) { GitHubUpdate.parse(pending, "0.4.9") }
        }
    }
}
