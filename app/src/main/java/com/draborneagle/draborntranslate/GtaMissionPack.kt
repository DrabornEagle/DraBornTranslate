// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import com.gametranslator.gametranslator_mobile.core.PackKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal enum class GtaMissionType(val key: String, val label: String) {
    STORY("story", "Ana hikâye"), SIDE("side", "Yan hikâye"), OBJECTIVE("objective", "Görev hedefi"),
}

internal enum class GtaMissionSearchLanguage(val label: String, val searchHint: String) {
    TURKISH("Türkçe", "Türkçe görev adı"),
    ENGLISH("İngilizce", "İngilizce görev adı veya kod");

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: TURKISH
    }
}

internal enum class GtaTextType(val key: String, val label: String) {
    DIALOGUE("dialogue", "Diyalog"), OBJECTIVE("objective", "Hedef"), MESSAGE("message", "Mesaj"),
    HINT("hint", "İpucu"), INTERFACE("ui", "Arayüz"), GUIDE("guide", "Görev rehberi"), OTHER("other", "Diğer"),
}

internal data class GtaMissionImage(val url: String, val caption: String? = null)

internal data class GtaMissionScene(
    val id: String,
    val title: String,
    val environment: String?,
    val context: String?,
)

internal data class GtaMission(
    val id: String,
    val title: String,
    val englishTitle: String?,
    val type: GtaMissionType,
    val chapter: Int?,
    val series: String?,
    val summary: String,
    val location: String?,
    val characters: List<String>,
    val scenes: List<GtaMissionScene>,
    val source: String?,
    val textSource: String?,
    val textCount: Int,
    val aliases: List<String>,
    val prerequisites: List<String>,
    val mapLabels: List<String> = emptyList(),
    val giver: String? = null,
    val profileSource: String? = null,
    val gallerySource: String? = null,
    val gameDataSource: String? = null,
    val previews: List<GtaMissionImage> = emptyList(),
    val dialogueSource: String? = null,
) {
    val category: String get() = listOfNotNull(type.label, chapter?.let { "Bölüm $it" }, series).joinToString(" · ")
    val code: String? get() = (listOfNotNull(englishTitle, title) + aliases).firstOrNull { missionCode.matches(it) }
    private val englishName = englishTitle?.takeUnless(missionCode::matches)
    private val turkishName = title.takeUnless(missionCode::matches)
    val searchNames = (listOfNotNull(title, englishTitle, id) + mapLabels + aliases).map(::missionNameKey).distinct()

    fun nameIn(language: GtaMissionSearchLanguage): String {
        val names = if (language == GtaMissionSearchLanguage.ENGLISH) englishName to turkishName else turkishName to englishName
        return names.first ?: names.second ?: "Görev adı doğrulanmadı"
    }

    fun alternateNameIn(language: GtaMissionSearchLanguage): String? {
        return (if (language == GtaMissionSearchLanguage.ENGLISH) turkishName else englishName)
            ?.takeUnless { it == nameIn(language) }
    }
}

private val missionCode = Regex("[A-Z0-9]+(?:_[A-Z0-9]+)+")
private val missionApostrophes = Regex("['’‘ʼ]")
internal fun missionNameKey(text: String) = PackKey.canon(text.replace(missionApostrophes, "")).replace('ı', 'i')

internal data class GtaMissionCatalog(
    val version: String,
    val gameBuild: String,
    val missions: List<GtaMission>,
    private val portraits: Map<String, GtaMissionImage> = emptyMap(),
) {
    fun portrait(giver: String?): GtaMissionImage? =
        giver?.split(" / ")?.firstNotNullOfOrNull { portraits[missionNameKey(it)] }

    fun search(query: String, language: GtaMissionSearchLanguage = GtaMissionSearchLanguage.TURKISH): List<GtaMission> {
        val name = missionNameKey(query)
        if (name.isEmpty()) return missions
        val words = name.split(' ')
        return missions.mapNotNull { mission ->
            val names = mission.searchNames
            val rank = when {
                name in names -> 0
                names.any { it.startsWith(name) } -> 1
                names.any { value -> words.all { value.contains(it) } } -> 2
                else -> return@mapNotNull null
            }
            val preferred = missionNameKey(mission.nameIn(language))
            val matchesPreferred = when (rank) {
                0 -> name == preferred
                1 -> preferred.startsWith(name)
                else -> words.all { preferred.contains(it) }
            }
            mission to (rank * 2 + if (matchesPreferred) 0 else 1)
        }.sortedBy { it.second }.map { it.first }
    }
}

internal data class GtaMissionText(
    val type: GtaTextType,
    val turkish: String?,
    val english: String?,
    val sceneId: String?,
    val speaker: String?,
    val context: String?,
    val startMs: Long?,
    val endMs: Long?,
)

internal object GtaMissionPack {
    const val MAX_ARCHIVE_BYTES = 48 * 1024 * 1024
    private const val MAX_CATALOG_BYTES = 2 * 1024 * 1024
    private const val MAX_MISSION_TEXT_BYTES = 6 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 192L * 1024 * 1024
    private const val MAX_MISSIONS = 1500
    private const val CATALOG = "catalog.json"
    private val identifier = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

    fun catalog(file: File): GtaMissionCatalog = read(file) { catalog(it) }

    fun validate(file: File): GtaMissionCatalog = read(file) { zip ->
        val catalog = catalog(zip)
        val expected = catalog.missions.filter { it.textCount > 0 }.mapTo(mutableSetOf(CATALOG)) { textsPath(it.id) }
        var expanded = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            checkPack(!entry.isDirectory && expected.remove(entry.name))
            val limit = if (entry.name == CATALOG) MAX_CATALOG_BYTES else MAX_MISSION_TEXT_BYTES
            checkPack(entry.size in 1L..limit.toLong())
            expanded += entry.size
            checkPack(expanded <= MAX_EXPANDED_BYTES)
        }
        checkPack(expected.isEmpty())
        catalog.missions.filter { it.textCount > 0 }.forEach { texts(zip, it) }
        catalog
    }

    fun texts(file: File, catalogVersion: String, mission: GtaMission): List<GtaMissionText> = read(file) { zip ->
        if (catalog(zip).version != catalogVersion) throw IOException("Görev paketi güncellendi. Görev aramasını yeniden aç.")
        texts(zip, mission)
    }

    private fun catalog(zip: ZipFile): GtaMissionCatalog {
        val data = JSONObject(readEntry(zip, CATALOG, MAX_CATALOG_BYTES))
        checkPack(data.optInt("format") == 1)
        val entries = data.getJSONArray("missions")
        checkPack(entries.length() in 1..MAX_MISSIONS)
        val media = data.optJSONObject("media")
        val galleries = media?.optJSONObject("previews")
        val dialogues = media?.optJSONObject("dialogueSources")
        val portraits = media?.optJSONObject("portraits")?.let { values ->
            checkPack(values.length() <= MAX_MISSIONS)
            values.keys().asSequence().associate { name ->
                checkPack(name.isNotBlank() && name.length <= 120)
                missionNameKey(name) to GtaMissionImage(imageUrl(values.requiredText(name)))
            }
        }.orEmpty()
        checkPack((galleries?.length() ?: 0) <= MAX_MISSIONS && (dialogues?.length() ?: 0) <= MAX_MISSIONS)
        val missions = entries.objects().map { entry ->
            val id = entry.id()
            val previews = galleries?.optJSONArray(id)?.let { items ->
                checkPack(items.length() in 1..3)
                items.objects().map { image ->
                    GtaMissionImage(imageUrl(image.requiredText("url")),
                        image.text("captionTr")?.also { checkPack(it.length <= 120) })
                }
            }.orEmpty()
            mission(entry, previews, dialogues?.sourceUrl(id))
        }
        val ids = missions.mapTo(mutableSetOf()) { it.id }
        checkPack(ids.size == missions.size && missions.all { mission ->
            mission.prerequisites.all { it in ids && it != mission.id }
        })
        checkPack(galleries?.keys()?.asSequence()?.all { it in ids } != false &&
            dialogues?.keys()?.asSequence()?.all { it in ids } != false)
        return GtaMissionCatalog(data.requiredText("version"), data.requiredText("gameBuild"), missions, portraits)
    }

    private fun mission(data: JSONObject, previews: List<GtaMissionImage>, dialogue: String?): GtaMission {
        val scenes = data.optJSONArray("scenes")?.objects()?.map { scene ->
            GtaMissionScene(scene.id(), scene.requiredText("titleTr"), scene.text("environmentTr"), scene.text("contextTr"))
        }.orEmpty()
        checkPack(scenes.map { it.id }.toSet().size == scenes.size)
        val type = GtaMissionType.entries.firstOrNull { it.key == data.text("type") } ?: invalid()
        val chapter = data.optionalNumber("chapter")?.also { checkPack(it in 1L..5L) }?.toInt()
        val count = data.optionalNumber("textCount") ?: 0L
        checkPack(count in 0L..30_000L)
        return GtaMission(data.id(), data.requiredText("titleTr"), data.text("titleEn"), type, chapter,
            data.text("series"), data.requiredText("summaryTr"), data.text("locationTr"), data.strings("characters"),
            scenes, data.sourceUrl("sourceUrl"), data.sourceUrl("textSourceUrl"), count.toInt(), data.strings("aliases"),
            data.strings("requires"), data.strings("mapLabels"), data.text("giver"),
            data.sourceUrl("profileSourceUrl"), data.sourceUrl("gallerySourceUrl"), data.sourceUrl("gameDataSourceUrl"),
            previews, dialogue ?: data.sourceUrl("textSourceUrl")?.takeIf { it.startsWith("https://six.mics.dev/dialogue?") })
    }

    private fun texts(zip: ZipFile, mission: GtaMission): List<GtaMissionText> {
        if (mission.textCount == 0) return emptyList()
        val entries = JSONArray(readEntry(zip, textsPath(mission.id), MAX_MISSION_TEXT_BYTES))
        checkPack(entries.length() == mission.textCount)
        val knownScenes = mission.scenes.mapTo(mutableSetOf()) { it.id }
        return entries.objects().map { data ->
            val type = GtaTextType.entries.firstOrNull { it.key == data.text("type") } ?: invalid()
            val sceneId = data.text("sceneId")?.also { checkPack(it in knownScenes) }
            val start = data.optionalNumber("startMs")
            val end = data.optionalNumber("endMs")
            checkPack(start == null || (sceneId != null && start >= 0L))
            checkPack(end == null || (start != null && end >= start))
            val turkish = data.text("tr")
            val english = data.text("en")
            checkPack(turkish != null || english != null)
            GtaMissionText(type, turkish, english, sceneId, data.text("speaker"),
                data.text("contextTr"), start, end)
        }
    }

    private fun textsPath(id: String) = "texts/$id.json"

    private fun readEntry(zip: ZipFile, path: String, limit: Int): String {
        val entry: ZipEntry = zip.getEntry(path) ?: invalid()
        checkPack(entry.size in 1L..limit.toLong())
        return zip.getInputStream(entry).use { input ->
            val data = readLimited(input, limit)
            checkPack(data.size.toLong() == entry.size)
            data.toString(Charsets.UTF_8)
        }
    }

    private fun <T> read(file: File, block: (ZipFile) -> T): T = try {
        checkPack(file.length() in 1L..MAX_ARCHIVE_BYTES.toLong())
        ZipFile(file).use(block)
    } catch (error: JSONException) {
        throw IOException("GTA 6 görev paketinin verileri geçersiz.", error)
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    private fun JSONObject.text(name: String) = (opt(name) as? String)?.trim()?.takeIf(String::isNotEmpty)
    private fun JSONObject.requiredText(name: String) = text(name) ?: invalid()
    private fun JSONObject.id() = requiredText("id").also { checkPack(it.length <= 80 && identifier.matches(it)) }

    private fun JSONObject.sourceUrl(name: String): String? = text(name)?.also { value ->
        val uri = runCatching { URI(value) }.getOrNull() ?: invalid()
        checkPack(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1, 443))
    }

    private fun imageUrl(url: String): String = url.also {
        checkPack(missionImageUri(it) != null)
    }

    private fun JSONObject.strings(name: String): List<String> {
        if (!has(name)) return emptyList()
        val array = getJSONArray(name)
        return List(array.length()) { (array.get(it) as? String)?.trim()?.takeIf(String::isNotEmpty) ?: invalid() }
    }

    private fun JSONObject.optionalNumber(name: String): Long? {
        if (!has(name)) return null
        return when (val value = get(name)) {
            is Int -> value.toLong()
            is Long -> value
            else -> invalid()
        }
    }

    private fun checkPack(valid: Boolean) { if (!valid) invalid() }
    private fun invalid(): Nothing = throw IOException("GTA 6 görev paketinin verileri geçersiz.")
}

internal fun missionImageUri(url: String): URI? {
    if (url.length > 640) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) || uri.fragment != null) return null
    val path = uri.rawPath ?: return null
    if (path.contains('%') || path.split('/').any { it == "." || it == ".." }) return null
    val supported = when (uri.host) {
        "www.rockstargames.com" -> path.startsWith("/VI/_next/static/media/")
        "www.gtabase.com" -> path.startsWith("/images/gta-6/") || path.startsWith("/images/jch-optimize/ng/")
        "www.powerpyx.com" -> path.startsWith("/wp-content/uploads/gta-6-")
        else -> false
    }
    return uri.takeIf { supported && path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "webp", "png", "avif") }
}
