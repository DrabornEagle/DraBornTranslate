// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import com.gametranslator.gametranslator_mobile.Settings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Çeviri dilleri listesi, motorların dil kodları, ilk kurulum varsayılanı ve arayüz dili kuralları. */
class LanguagesTest {
    @Test
    fun listIsTheSharedOneInOrder() {
        assertEquals(
            listOf("tr", "en", "ar", "ja", "de", "fr", "es", "it", "pt", "ru", "zh", "ko", "pl", "nl", "id", "hi", "fa", "uk", "el", "hu",
                "ro", "cs", "sv", "th", "vi", "he", "az"),
            Languages.targets.map { it.code })
        assertEquals(setOf("ar", "fa", "he"), Languages.targets.filter { it.rtl }.map { it.code }.toSet())
        assertEquals("简体中文", Languages.find("zh")!!.nativeName)
        assertEquals("Türkçe", Languages.find("tr")!!.nativeName)
        assertTrue(Languages.targets.all { it.nativeName.isNotBlank() && it.englishName.isNotBlank() && it.turkishName.isNotBlank() })
        // Oyunun dili listesi bugünküyle aynı ve hepsi ortak listede.
        assertEquals(listOf("tr", "en", "de", "fr", "es", "it", "pt", "ru", "ar", "ja"), Languages.sources)
        assertTrue(Languages.sources.all { Languages.find(it) != null })
    }

    @Test
    fun firstRunTargetIsThePhoneLanguageWhenListedOtherwiseEnglish() {
        assertEquals("tr", Languages.defaultTarget("tr"))
        assertEquals("ja", Languages.defaultTarget("ja"))
        assertEquals("ar", Languages.defaultTarget("AR"))
        assertEquals("he", Languages.defaultTarget("iw"))
        assertEquals("id", Languages.defaultTarget("in"))
        assertEquals("en", Languages.defaultTarget("da"))
        assertEquals("en", Languages.defaultTarget(""))
    }

    @Test
    fun googleTakesTheCodeAsIsExceptSimplifiedChinese() {
        assertEquals("zh-CN", Languages.googleCode("zh"))
        assertEquals("he", Languages.googleCode("he"))
        assertEquals("tr", Languages.googleCode("tr"))
        assertEquals("az", Languages.googleCode("az"))
    }

    @Test
    fun deepLUsesItsOwnCodes() {
        assertEquals("TR", Languages.deepLTarget("tr"))
        assertEquals("EN-US", Languages.deepLTarget("en"))
        assertEquals("ZH-HANS", Languages.deepLTarget("zh"))
        assertEquals("PT-BR", Languages.deepLTarget("pt"))
        assertEquals("PT-BR", Languages.deepLTarget("pt", "BR"))
        assertEquals("PT-PT", Languages.deepLTarget("pt", "PT"))
        assertEquals("JA", Languages.deepLTarget("ja"))
        // Listede olmayan bir dil: DeepL çeviremez sayılır.
        assertNull(Languages.deepLTarget("xx"))
        assertTrue(Languages.targets.all { Languages.deepLTarget(it.code) != null })
    }

    @Test
    fun engineThatCannotTranslateFallsBackToGoogle() {
        // Cihaz üstü çeviri Azerbaycanca bilmez.
        assertFalse(Languages.mlKitSupports("az"))
        assertEquals(listOf("az"), Languages.targets.map { it.code }.filterNot { Languages.mlKitSupports(it) })
        assertEquals("google", Languages.effectiveEngine("mlkit", "az"))
        assertEquals("mlkit", Languages.effectiveEngine("mlkit", "ja"))
        assertEquals("deepl", Languages.effectiveEngine("deepl", "az"))
        assertEquals("google", Languages.effectiveEngine("deepl", "xx"))
        assertEquals("google", Languages.effectiveEngine("google", "az"))
        // Türkçe kullanıcı için hiçbir motor değişmez.
        for (engine in listOf("google", "deepl", "mlkit")) assertEquals(engine, Languages.effectiveEngine(engine, "tr"))
    }

    @Test
    fun bridgeMapCarriesNamesDirectionAndEngineSupport() {
        val map = Languages.toMaps()
        @Suppress("UNCHECKED_CAST")
        val targets = map["targets"] as List<Map<String, Any>>
        assertEquals(27, targets.size)
        val az = targets.single { it["code"] == "az" }
        assertEquals(false, az["mlkit"])
        assertEquals(true, az["deepl"])
        val fa = targets.single { it["code"] == "fa" }
        assertEquals(true, fa["rtl"])
        assertEquals("فارسی", fa["native"])
        assertEquals("Persian", fa["english"])
        assertEquals("Farsça", fa["turkish"])
        assertEquals(Languages.sources, map["sources"])
    }

    @Test
    fun packsApplyOnlyForEnglishToTurkish() {
        assertTrue(PackTranslation.applies("en", "tr"))
        for (target in Languages.targets.map { it.code }.filter { it != "tr" }) assertFalse(PackTranslation.applies("en", target))
        assertFalse(PackTranslation.applies("ja", "tr"))
    }

    @Test
    fun uiLanguageFollowsThePhoneUnlessChosen() {
        assertEquals("tr", UiText.resolve("auto", "tr"))
        assertEquals("tr", UiText.resolve("auto", "TR"))
        assertEquals("en", UiText.resolve("auto", "en"))
        assertEquals("en", UiText.resolve("auto", "de"))
        assertEquals("en", UiText.resolve("en", "tr"))
        assertEquals("tr", UiText.resolve("tr", "ja"))
        assertEquals("en", UiText.resolve("xx", "ja"))
    }

    @Test
    fun nativeTablesHaveTheSameKeysAndCodesAreStable() {
        val keys = UiText.tables.getValue("tr").keys
        assertTrue(keys.isNotEmpty())
        for ((language, table) in UiText.tables) {
            assertEquals(language, keys, table.keys)
            assertTrue(table.values.all { it.isNotBlank() })
        }
        assertEquals("@rest_mode", UiText.code("rest_mode"))
        assertEquals("@not_found\u001f192.168.1.20", UiText.code("not_found", "192.168.1.20"))
    }

    @Test
    fun settingsKeepASavedTargetAndUseTheGivenDefaultOtherwise() {
        // Eski kurulum: kayıtta çeviri dili var → olduğu gibi kalır.
        val saved = Settings.fromJson(JSONObject().put("targetLanguage", "tr").put("engine", "deepl"), defaultTarget = "ja")
        assertEquals("tr", saved.targetLanguage)
        assertEquals("deepl", saved.engine)
        assertEquals("auto", saved.uiLanguage)
        // Kayıt yok ya da alan yok → verilen varsayılan (yeni kurulumda telefonun dili, eski kurulumda Türkçe).
        assertEquals("ja", Settings.fromJson(null, defaultTarget = "ja").targetLanguage)
        assertEquals("ja", Settings.fromJson(JSONObject().put("engine", "google"), defaultTarget = "ja").targetLanguage)
        assertEquals("tr", Settings.fromJson(null).targetLanguage)
        assertEquals("en", Settings.fromJson(null).sourceLanguage)
        // Arayüz dili ayarla birlikte saklanır.
        val round = Settings.fromJson(Settings(uiLanguage = "en", targetLanguage = "ar").toJson())
        assertEquals("en", round.uiLanguage)
        assertEquals("ar", round.targetLanguage)
    }
}
