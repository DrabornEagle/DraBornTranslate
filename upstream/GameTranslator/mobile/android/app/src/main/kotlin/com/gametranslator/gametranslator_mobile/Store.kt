// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile

import android.content.Context
import android.util.Base64
import com.gametranslator.gametranslator_mobile.core.ExtraAreaPreset
import com.gametranslator.gametranslator_mobile.core.ExtraAreas
import com.gametranslator.gametranslator_mobile.core.Glossary
import com.gametranslator.gametranslator_mobile.core.Languages
import com.gametranslator.gametranslator_mobile.core.SubtitleStyle
import com.gametranslator.gametranslator_mobile.core.TextBox
import com.gametranslator.gametranslator_mobile.core.UiText
import com.gametranslator.gametranslator_mobile.core.WatchedArea
import com.gametranslator.gametranslator_mobile.xbox.XboxConsole
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** PS5'e kaydedilmiş bu telefonun bağlantı bilgileri. */
class Console(val host: String, val name: String, val registKey: ByteArray, val morning: ByteArray) {
    /** Uyandırma için kayıt anahtarının onaltılık değeri (anahtar ASCII onaltılık, sıfırla doldurulmuş). */
    val wakeCredential: Long?
        get() {
            val end = registKey.indexOf(0).let { if (it < 0) registKey.size else it }
            val text = String(registKey, 0, end, Charsets.US_ASCII)
            return try { java.lang.Long.parseUnsignedLong(text, 16) } catch (error: NumberFormatException) { null }
        }
}

/** Altyazının oyunda arandığı bölge, ekrana oranla (0–1). */
data class Region(val top: Double = 0.62, val bottom: Double = 0.97, val left: Double = 0.14, val right: Double = 0.86) {
    /** Aynı bölge çekirdeğin kutu biçiminde (sol, üst, sağ, alt). */
    fun toBox() = TextBox(left, top, right, bottom)

    fun toJson(): JSONObject = JSONObject().put("top", top).put("bottom", bottom).put("left", left).put("right", right)

    companion object {
        fun of(box: TextBox) = Region(top = box.top, bottom = box.bottom, left = box.left, right = box.right)

        /** Kayıttaki bölge; kenarlar karenin içinde, en az %5 yükseklik ve genişlikte. */
        fun fromJson(r: JSONObject?, defaults: Region = Region()): Region {
            val top = (r?.optDouble("top", defaults.top) ?: defaults.top).coerceIn(0.0, 0.95)
            val left = (r?.optDouble("left", defaults.left) ?: defaults.left).coerceIn(0.0, 0.95)
            return Region(
                top = top,
                bottom = (r?.optDouble("bottom", defaults.bottom) ?: defaults.bottom).coerceIn(top + 0.05, 1.0),
                left = left,
                right = (r?.optDouble("right", defaults.right) ?: defaults.right).coerceIn(left + 0.05, 1.0))
        }
    }
}

/**
 * Ek alan yuvası (Windows: UserSettings.ExtraRegion1-3 ve ExtraLive1-3): [region] null ise yuva boş; [live] açıksa alan
 * sürekli okunup çevrilir. Telefonda bölge yalnız hazır seçimlerden gelir ([ExtraAreaPreset]).
 */
data class ExtraSlot(val region: Region? = null, val live: Boolean = false)

data class Settings(
    val region: Region = Region(),
    val sourceLanguage: String = "en",
    val targetLanguage: String = "tr",
    /** google (çevrimiçi, anahtarsız), mlkit (cihaz üstünde), deepl (kendi anahtarınla) */
    val engine: String = "google",
    val deepLKey: String = "",
    val style: SubtitleStyle = SubtitleStyle(),
    /** PS5 dinlenme modundaysa uyandır. */
    val autoWake: Boolean = true,
    /** Terim sözlüğü, bilgisayardaki sözlük dosyalarıyla aynı JSON biçiminde; boşsa sözlük yok. */
    val glossary: String = "",
    /** Oyun paketleri (İngilizce→Türkçe): paketteki satır motora gitmeden paketin Türkçesiyle gösterilir. */
    val packsEnabled: Boolean = true,
    /** Arayüz dili: "auto" (telefonun dili) ya da bir tablo kodu ("tr", "en"). */
    val uiLanguage: String = UiText.AUTO,
    /** Ortak havuz: pakette olmayan İngilizce satırlar yeni paketler için gönderilir (varsayılan açık). */
    val sharePool: Boolean = true,
    /**
     * Altyazının yeri kendiliğinden bulunur ([com.gametranslator.gametranslator_mobile.core.SubtitleFinder]);
     * [region] yalnız kapalıyken kullanılır. Yeni kurulumda açık; ayarları kayıtlı eski kurulumda elle seçilen bölge kalır.
     */
    val autoRegion: Boolean = true,
    /** Sürekli okunan ek alanların yuvaları ([ExtraAreas.SLOTS] tane; varsayılan hepsi boş ve kapalı). */
    val extraAreas: List<ExtraSlot> = List(ExtraAreas.SLOTS) { ExtraSlot() },
    /**
     * Ek alanlarda tanınan tuş simgelerinin kumandası: "auto" (Xbox kaynağında Xbox, PS5'te PlayStation), "playstation"
     * ya da "xbox" (Windows: UserSettings.ButtonLayout).
     */
    val buttonLayout: String = "auto",
) {
    /** 1, 2 ya da 3 numaralı ek alanın bölgesi; boşsa null. */
    fun extraRegion(slot: Int): Region? = extraAreas.getOrNull(slot - 1)?.region

    fun extraLive(slot: Int): Boolean = extraAreas.getOrNull(slot - 1)?.live == true

    private fun withSlot(slot: Int, edit: (ExtraSlot) -> ExtraSlot): Settings =
        if (slot !in 1..ExtraAreas.SLOTS) this
        else copy(extraAreas = List(ExtraAreas.SLOTS) { i -> extraAreas.getOrElse(i) { ExtraSlot() }.let { if (i == slot - 1) edit(it) else it } })

    fun withExtraRegion(slot: Int, region: Region?): Settings = withSlot(slot) { it.copy(region = region) }

    fun withExtraLive(slot: Int, live: Boolean): Settings = withSlot(slot) { it.copy(live = live) }

    /** Hazır ek alan sürekli okunuyor mu. */
    fun hasExtraPreset(preset: ExtraAreaPreset): Boolean =
        (1..ExtraAreas.SLOTS).any { slot -> extraLive(slot) && extraRegion(slot) == Region.of(preset.region) }

    /**
     * Hazır ek alanı açar ya da kapatır (Windows: WithExtraPreset). Açarken sürekli okunmayan ilk yuva kullanılır (aynı
     * bölgeli yuva önce); üç yuva da doluysa ayar değişmez. Kapatınca yuvanın bölgesi kalır.
     */
    fun withExtraPreset(preset: ExtraAreaPreset, on: Boolean): Settings {
        val region = Region.of(preset.region)
        if (!on) {
            var result = this
            for (slot in 1..ExtraAreas.SLOTS) if (result.extraRegion(slot) == region) result = result.withExtraLive(slot, false)
            return result
        }
        if (hasExtraPreset(preset)) return this
        val free = (1..ExtraAreas.SLOTS).filter { !extraLive(it) }.sortedBy { if (extraRegion(it) == region) 0 else 1 }.firstOrNull()
            ?: return this
        return withExtraRegion(free, region).withExtraLive(free, true)
    }

    /**
     * GTA 6 için tek dokunuş (Windows: WithGta6Reading): altyazı otomatik bulunur, üç ek alan (alttaki görev yazısı,
     * soldaki ipucu kutusu, sağ alttaki telefon mesajı) sürekli okunur.
     */
    fun withGta6Reading(): Settings = copy(autoRegion = true)
        .withExtraRegion(1, Region.of(ExtraAreaPreset.BOTTOM_OBJECTIVE.region)).withExtraLive(1, true)
        .withExtraRegion(2, Region.of(ExtraAreaPreset.HELP_BOX.region)).withExtraLive(2, true)
        .withExtraRegion(3, Region.of(ExtraAreaPreset.PHONE_MESSAGE.region)).withExtraLive(3, true)

    /** Sürekli okunan ek alanlar (bölgesi olan ve açık yuvalar). */
    fun watchedAreas(): List<WatchedArea> = (1..ExtraAreas.SLOTS).mapNotNull { slot ->
        val region = extraRegion(slot)
        if (!extraLive(slot) || region == null) null else WatchedArea("area$slot", "Alan $slot", region.toBox())
    }

    /** Sözlük metni çözülmüş hâliyle; boş ya da bozuksa boş sözlük. */
    fun parsedGlossary(): Glossary {
        if (glossary.isBlank()) return Glossary.EMPTY
        val root = try { JSONObject(glossary) } catch (error: org.json.JSONException) { return Glossary.EMPTY }
        val terms = LinkedHashMap<String, String>()
        root.optJSONObject("terms")?.let { map -> for (key in map.keys()) (map.opt(key) as? String)?.let { terms[key] = it } }
        val keep = ArrayList<String>()
        root.optJSONArray("keep")?.let { list -> for (i in 0 until list.length()) (list.opt(i) as? String)?.let { keep.add(it) } }
        return Glossary.of(terms, keep)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("region", JSONObject().put("top", region.top).put("bottom", region.bottom).put("left", region.left).put("right", region.right))
        .put("sourceLanguage", sourceLanguage)
        .put("targetLanguage", targetLanguage)
        .put("engine", engine)
        .put("deepLKey", deepLKey)
        .put("autoWake", autoWake)
        .put("glossary", glossary)
        .put("packsEnabled", packsEnabled)
        .put("uiLanguage", uiLanguage)
        .put("sharePool", sharePool)
        .put("autoRegion", autoRegion)
        .put("buttonLayout", buttonLayout)
        // Yuva: bölge, sürekli okuma ve (bölge hazır seçimse) seçimin adı; arayüz hazır seçimleri adıyla açıp kapatır.
        .put("extraAreas", JSONArray().also { list ->
            for (slot in 1..ExtraAreas.SLOTS) {
                val region = extraRegion(slot)
                if (region == null) list.put(JSONObject.NULL)
                else list.put(JSONObject().put("region", region.toJson()).put("live", extraLive(slot))
                    .also { item -> ExtraAreaPreset.matching(region.toBox())?.let { item.put("preset", it.key) } })
            }
        })
        .put("style", JSONObject()
            .put("fontSize", style.fontSize).put("bottomPercent", style.bottomPercent).put("placement", style.placement)
            .put("background", style.background).put("bold", style.bold).put("textColor", style.textColor)
            .put("displayMode", style.displayMode).put("stackPrevious", style.stackPrevious)
            .put("speakerColor", style.speakerColor).put("font", style.font)
            .put("regionBackground", style.regionBackground).put("regionScale", style.regionScale)
            .put("regionColor", style.regionColor))

    companion object {
        /**
         * Eksik alanlar varsayılanla dolar. [defaultTarget]: kayıtta çeviri dili yoksa kullanılacak dil
         * ([Store.settings] ilk kurulumda telefonun dilini, eski kurulumda Türkçeyi verir).
         */
        fun fromJson(json: JSONObject?, defaultTarget: String = "tr"): Settings {
            val defaults = Settings(targetLanguage = defaultTarget)
            if (json == null) return defaults
            val s = json.optJSONObject("style")
            val d = defaults.style
            val extras = json.optJSONArray("extraAreas")
            return Settings(
                region = Region.fromJson(json.optJSONObject("region"), defaults.region),
                // Hazır seçimin adı yazılıysa bölgesi addan gelir (arayüz yalnız adı yazar); yoksa kayıtlı bölge.
                extraAreas = List(ExtraAreas.SLOTS) { i ->
                    val item = extras?.optJSONObject(i) ?: return@List ExtraSlot()
                    val preset = ExtraAreaPreset.byKey(item.optString("preset", "").ifEmpty { null })
                    val region = preset?.let { Region.of(it.region) } ?: item.optJSONObject("region")?.let { Region.fromJson(it) }
                    ExtraSlot(region, region != null && item.optBoolean("live", false))
                },
                sourceLanguage = json.optString("sourceLanguage", defaults.sourceLanguage),
                targetLanguage = json.optString("targetLanguage", defaults.targetLanguage),
                engine = json.optString("engine", defaults.engine),
                deepLKey = json.optString("deepLKey", ""),
                autoWake = json.optBoolean("autoWake", true),
                glossary = json.optString("glossary", ""),
                packsEnabled = json.optBoolean("packsEnabled", true),
                uiLanguage = json.optString("uiLanguage", UiText.AUTO),
                sharePool = json.optBoolean("sharePool", true),
                // Kayıtlı ayarlarda alan yoksa kullanıcı bölgeyi elle seçmişti: bölgesi değişmesin.
                autoRegion = json.optBoolean("autoRegion", false),
                buttonLayout = com.gametranslator.gametranslator_mobile.core.ButtonLayout.parse(json.optString("buttonLayout", "auto")).key,
                style = SubtitleStyle(
                    fontSize = s?.optInt("fontSize", d.fontSize) ?: d.fontSize,
                    bottomPercent = s?.optInt("bottomPercent", d.bottomPercent) ?: d.bottomPercent,
                    placement = s?.optString("placement", d.placement) ?: d.placement,
                    background = s?.optInt("background", d.background) ?: d.background,
                    bold = s?.optBoolean("bold", d.bold) ?: d.bold,
                    textColor = s?.optString("textColor", d.textColor) ?: d.textColor,
                    displayMode = s?.optString("displayMode", d.displayMode) ?: d.displayMode,
                    stackPrevious = s?.optBoolean("stackPrevious", d.stackPrevious) ?: d.stackPrevious,
                    speakerColor = s?.optBoolean("speakerColor", d.speakerColor) ?: d.speakerColor,
                    font = s?.optString("font", d.font) ?: d.font,
                    regionBackground = (s?.optInt("regionBackground", d.regionBackground) ?: d.regionBackground).coerceIn(0, 100),
                    regionScale = (s?.optInt("regionScale", d.regionScale) ?: d.regionScale).coerceIn(70, 150),
                    regionColor = s?.optString("regionColor", d.regionColor) ?: d.regionColor))
        }
    }
}

/** Uygulamaya özel depoda (başka uygulamalar okuyamaz) tutulan kayıtlar. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gametranslator", Context.MODE_PRIVATE)

    var accountId: ByteArray?
        get() = prefs.getString("accountId", null)?.let { decode(it) }?.takeIf { it.size == 8 }
        set(value) = prefs.edit().apply { if (value == null) remove("accountId") else putString("accountId", encode(value)) }.apply()

    var console: Console?
        get() {
            val json = prefs.getString("console", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val key = decode(json.optString("registKey")) ?: return null
            val morning = decode(json.optString("morning")) ?: return null
            if (key.size != 16 || morning.size != 16 || json.optString("host").isEmpty()) return null
            return Console(json.optString("host"), json.optString("name", "PS5"), key, morning)
        }
        set(value) = prefs.edit().apply {
            if (value == null) remove("console")
            else putString("console", JSONObject().put("host", value.host).put("name", value.name)
                .put("registKey", encode(value.registKey)).put("morning", encode(value.morning)).toString())
        }.apply()

    /** Görüntünün alındığı konsol: [KIND_PS5] (varsayılan, Remote Play) ya da [KIND_XBOX] (Beta, ev yayını). */
    var consoleKind: String
        get() = if (prefs.getString("consoleKind", null) == KIND_XBOX) KIND_XBOX else KIND_PS5
        set(value) = prefs.edit().putString("consoleKind", if (value == KIND_XBOX) KIND_XBOX else KIND_PS5).apply()

    /** Seçilen Xbox (kimlik, ad). Microsoft giriş jetonu burada değil, [SecretStore]'da durur. */
    var xboxConsole: XboxConsole?
        get() = XboxConsole.fromJson(prefs.getString("xboxConsole", null))
        set(value) = prefs.edit().apply { if (value == null) remove("xboxConsole") else putString("xboxConsole", value.toJson()) }.apply()

    /** Tanıtım ekranları bir kez gösterildi mi? */
    var introSeen: Boolean
        get() = prefs.getBoolean("introSeen", false)
        set(value) = prefs.edit().putBoolean("introSeen", value).apply()

    /** Sürüm duyurusu [id] gösterildi mi (her duyuru bir kez). */
    fun noticeSeen(id: String): Boolean = prefs.getBoolean("notice.$id", false)

    fun setNoticeSeen(id: String) = prefs.edit().putBoolean("notice.$id", true).apply()

    /** "Bu telefonda göster" ekranındaki yazı ölçeği (1 = varsayılan). */
    var screenScale: Float
        get() = prefs.getFloat("screenScale", 1f).coerceIn(SCREEN_SCALE_MIN, SCREEN_SCALE_MAX)
        set(value) = prefs.edit().putFloat("screenScale", value.coerceIn(SCREEN_SCALE_MIN, SCREEN_SCALE_MAX)).apply()

    /** "Bu telefonda göster" ekranında önceki satır soluk olarak üstte kalsın mı? */
    var screenPrevious: Boolean
        get() = prefs.getBoolean("screenPrevious", true)
        set(value) = prefs.edit().putBoolean("screenPrevious", value).apply()

    /** "Bilgisayarın altyazısını göster" ekranının en son bağlandığı sunucu ("adres:port"); yoksa null. */
    var displayServer: String?
        get() = prefs.getString("displayServer", null)
        set(value) = prefs.edit().apply { if (value == null) remove("displayServer") else putString("displayServer", value) }.apply()

    var settings: Settings
        get() = Settings.fromJson(prefs.getString("settings", null)?.let { runCatching { JSONObject(it) }.getOrNull() }, defaultTarget())
        set(value) = prefs.edit().putString("settings", value.toJson().toString()).apply()

    /**
     * Çeviri dili hiç seçilmemişse kullanılacak dil; ilk soruluşta belirlenir ve saklanır. Uygulamanın daha önce
     * kullanıldığı kurulumlarda (herhangi bir kayıt var) eskisi gibi Türkçe; yeni kurulumda telefonun dili listede
     * varsa o, yoksa İngilizce.
     */
    private fun defaultTarget(): String {
        prefs.getString("defaultTarget", null)?.let { return it }
        val value = if (prefs.all.isNotEmpty()) "tr" else Languages.defaultTarget(Locale.getDefault().language)
        prefs.edit().putString("defaultTarget", value).apply()
        return value
    }

    companion object {
        const val KIND_PS5 = "ps5"
        const val KIND_XBOX = "xbox"
        const val SCREEN_SCALE_MIN = 0.5f
        const val SCREEN_SCALE_MAX = 3f

        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun decode(text: String): ByteArray? = try { Base64.decode(text, Base64.DEFAULT) } catch (error: IllegalArgumentException) { null }
    }
}
