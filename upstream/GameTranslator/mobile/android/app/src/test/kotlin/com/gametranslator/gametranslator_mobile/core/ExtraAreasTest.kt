// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import com.gametranslator.gametranslator_mobile.Region
import com.gametranslator.gametranslator_mobile.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ek alan yuvaları, hazır seçimler, GTA 6 ayarı ve altyazı okumasının ek alanları atlaması (Windows: ExtraRegionTests). */
class ExtraAreasTest {
    @Test
    fun liveAreasAreOffByDefault() {
        val settings = Settings()
        assertTrue(settings.watchedAreas().isEmpty())
        for (slot in 1..ExtraAreas.SLOTS) {
            assertFalse(settings.extraLive(slot))
            assertNull(settings.extraRegion(slot))
        }
    }

    /** GTA 6 için tek dokunuş: altyazı otomatik, üç ek alan (görev, ipucu kutusu, telefon mesajı) sürekli okunur. */
    @Test
    fun gta6ReadingTurnsOnItsThreeAreas() {
        val settings = Settings(autoRegion = false).withGta6Reading()
        assertTrue(settings.autoRegion)
        assertEquals(3, settings.watchedAreas().size)
        assertTrue(settings.hasExtraPreset(ExtraAreaPreset.BOTTOM_OBJECTIVE))
        assertTrue(settings.hasExtraPreset(ExtraAreaPreset.HELP_BOX))
        assertTrue(settings.hasExtraPreset(ExtraAreaPreset.PHONE_MESSAGE))
        assertEquals(listOf("area1", "area2", "area3"), settings.watchedAreas().map { it.id })
    }

    /** Hazır alan boş ilk yuvaya girer; üç yuva doluysa ayar değişmez; kapatınca yuva boşalır. */
    @Test
    fun presetAreasFillFreeSlotsUpToThree() {
        val settings = Settings()
            .withExtraPreset(ExtraAreaPreset.HELP_BOX, true)
            .withExtraPreset(ExtraAreaPreset.PHONE_MESSAGE, true)
            .withExtraPreset(ExtraAreaPreset.QUEST, true)
        assertEquals(3, settings.watchedAreas().size)
        val full = settings.withExtraPreset(ExtraAreaPreset.NOTIFICATION, true)
        assertFalse(full.hasExtraPreset(ExtraAreaPreset.NOTIFICATION))
        assertEquals(settings, full)
        val freed = settings.withExtraPreset(ExtraAreaPreset.PHONE_MESSAGE, false).withExtraPreset(ExtraAreaPreset.NOTIFICATION, true)
        assertTrue(freed.hasExtraPreset(ExtraAreaPreset.NOTIFICATION))
        assertFalse(freed.hasExtraPreset(ExtraAreaPreset.PHONE_MESSAGE))
        assertEquals(3, freed.watchedAreas().size)
        // Aynı alanı yeniden açmak yuva harcamaz.
        assertEquals(settings, settings.withExtraPreset(ExtraAreaPreset.QUEST, true))
    }

    @Test
    fun onlyConfiguredLiveRegionsAreWatched() {
        val quest = Region.of(ExtraAreaPreset.QUEST.region)
        val settings = Settings().withExtraRegion(1, quest).withExtraLive(1, true).withExtraRegion(2, Region()).withExtraLive(3, true)
        val watched = settings.watchedAreas()
        assertEquals(1, watched.size)
        assertEquals("area1", watched[0].id)
        assertEquals(ExtraAreaPreset.QUEST.region, watched[0].region)
    }

    /** Yuvalar ayar dosyasında kalır; arayüzün yalnız adını yazdığı hazır seçimin bölgesi addan gelir. */
    @Test
    fun extraAreasSurviveTheSettingsFile() {
        val settings = Settings().withGta6Reading().withExtraPreset(ExtraAreaPreset.HELP_BOX, false)
        val json = settings.toJson()
        assertEquals("bottom_objective", json.getJSONArray("extraAreas").getJSONObject(0).getString("preset"))
        val back = Settings.fromJson(JSONObject(json.toString()))
        assertEquals(settings.extraAreas, back.extraAreas)
        assertEquals(2, back.watchedAreas().size)

        val fromUi = JSONObject().put("extraAreas", JSONArray()
            .put(JSONObject().put("preset", "phone_message").put("live", true))
            .put(JSONObject.NULL)
            .put(JSONObject().put("preset", "no_such_preset").put("live", true)))
        val parsed = Settings.fromJson(fromUi)
        assertTrue(parsed.hasExtraPreset(ExtraAreaPreset.PHONE_MESSAGE))
        assertEquals(1, parsed.watchedAreas().size)
        assertNull(parsed.extraRegion(3))
        // Eski kayıtta alan yoktur: yuvalar boş.
        assertTrue(Settings.fromJson(JSONObject().put("engine", "google")).watchedAreas().isEmpty())
    }

    /** Altyazı okuması ek alanların içine düşen satırları atlar (GTA 6'da alttaki görev yazısı altyazı şeridinde). */
    @Test
    fun subtitleReadingSkipsLinesInsideExtraAreas() {
        val watched = Settings().withGta6Reading().watchedAreas()
        val subtitle = TextLine("Lucia: Get in, we're late.", TextBox(0.26, 0.84, 0.74, 0.88))
        val objective = TextLine("Follow Brian", TextBox(0.46, 0.94, 0.54, 0.97))
        val kept = ExtraAreas.withoutWatchedAreas(listOf(subtitle, objective), watched)
        assertEquals(listOf("Lucia: Get in, we're late."), kept.map { it.text })
        assertSame(kept, ExtraAreas.withoutWatchedAreas(kept, emptyList()))
    }

    /** Ek alana düşen altyazı satırı ve uzun altyazının alana taşan ucu ek alanda çevrilmez. */
    @Test
    fun subtitleLinesAreSkippedInExtraAreas() {
        assertTrue(ExtraAreas.skipLine("Lucia: Get in, we're late.", null))
        assertTrue(ExtraAreas.skipLine("ot it?", "Have you got it?"))
        assertTrue(ExtraAreas.skipLine("Have you got it", "Have you got it?"))
        assertFalse(ExtraAreas.skipLine("Follow Brian", "Have you got it?"))
        assertFalse(ExtraAreas.skipLine("Follow Brian", null))
    }

    @Test
    fun presetsAreFoundByNameAndRegion() {
        for (preset in ExtraAreaPreset.entries) {
            assertEquals(preset, ExtraAreaPreset.byKey(preset.key))
            assertEquals(preset, ExtraAreaPreset.matching(preset.region))
            assertTrue(preset.region.left < preset.region.right && preset.region.top < preset.region.bottom)
        }
        assertNull(ExtraAreaPreset.byKey("x"))
    }

    @Test
    fun gta6ObjectiveIsKeptOutOfTheSubtitleEvenWithoutExtraAreas() {
        val subtitle = TextLine("Cal: I've been sitting here all day.", TextBox(0.30, 0.86, 0.70, 0.90))
        val objective = TextLine("Drive the boat", TextBox(0.42, 0.94, 0.58, 0.97))
        org.junit.Assert.assertTrue(ExtraAreas.isGta6("Grand Theft Auto VI", ""))
        org.junit.Assert.assertTrue(ExtraAreas.isGta6("", "gta-6"))
        org.junit.Assert.assertFalse(ExtraAreas.isGta6("Grand Theft Auto V", "gta-5"))
        org.junit.Assert.assertEquals(listOf(subtitle), ExtraAreas.withoutGta6Objective(listOf(subtitle, objective), gta6 = true))
        org.junit.Assert.assertEquals(listOf(subtitle, objective), ExtraAreas.withoutGta6Objective(listOf(subtitle, objective), gta6 = false))
    }
}
