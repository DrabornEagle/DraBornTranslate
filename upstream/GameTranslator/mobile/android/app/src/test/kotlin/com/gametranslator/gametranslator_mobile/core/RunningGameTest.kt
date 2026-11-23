// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** PS5'te çalışan oyun (Windows: Ps5StatusTests). */
class Ps5StatusTest {
    @Test
    fun runningGameIsReadFromTheDiscoveryReply() {
        val info = Ps5Status.parse("HTTP/1.1 200 Ok\nhost-id:ABC\nhost-type:PS5\nhost-name:PS5-471\nhost-request-port:997\n" +
            "running-app-titleid:PPSA01234_00\nrunning-app-name:Mafia: The Old Country\n")
        assertNotNull(info)
        assertTrue(info!!.awake)
        assertEquals("PS5-471", info.hostName)
        assertEquals(RunningGame("PPSA01234_00", "Mafia: The Old Country"), info.game)
    }

    @Test
    fun restingOrHomeScreenHasNoGame() {
        val resting = Ps5Status.parse("HTTP/1.1 620 Server Standby\nhost-name:PS5-471\n")
        assertFalse(resting!!.awake)
        assertNull(resting.game)
        assertNull(Ps5Status.parse("SRCH * HTTP/1.1\n"))
    }

    @Test
    fun titleIdMustLookLikeAPs5IdAndNameIsTidied() {
        assertEquals(RunningGame("CUSA00001", null), RunningGame.create(" cusa00001 ", "  "))
        assertNull(RunningGame.create("NOTANID", "Game"))
        assertNull(RunningGame.create(null, "Game"))
        assertEquals("Grand Theft Auto V", RunningGame.normalizedName("Grand\u0000 Theft\tAuto   V​"))
        assertEquals(RunningGame.MAX_NAME, RunningGame.normalizedName("x".repeat(300))!!.length)
    }

    @Test
    fun noReplyGivesNull() {
        // Adres değil (ad çözümlemesi yapılmaz) ve yanıt vermeyen yerel adres.
        assertNull(Ps5Status.query("not-an-address", 200))
        assertNull(Ps5Status.query("127.0.0.1", 200))
    }

    private fun pack(id: String, title: String, line: String = "Come on.", turkish: String = "Hadi.") =
        "$id.json" to { "{\"format\":1,\"id\":\"$id\",\"title\":\"$title\",\"entries\":{\"${PackKey.key(line)}\":\"$turkish\"}}".toByteArray() }

    /** PS5'in bildirdiği ad paketin başlığıyla eşleşir; uyan paket elle seçilmiş gibi kullanılır. */
    @Test
    fun runningGameNameFindsItsPack() {
        val library = GamePackLibrary({
            listOf(
                pack("mafia-the-old-country", "Mafia: The Old Country"),
                pack("gta-v", "Grand Theft Auto V"),
                pack("death-stranding-2", "Death Stranding 2: On the Beach"),
            )
        }, null)
        assertEquals("mafia-the-old-country", library.findByGameName("Mafia: The Old Country"))
        assertEquals("gta-v", library.findByGameName("Grand Theft Auto V"))
        assertEquals("death-stranding-2", library.findByGameName("DEATH STRANDING 2: ON THE BEACH"))
        assertNull(library.findByGameName("FINAL FANTASY VII REMAKE INTERGRADE"))
        assertFalse(library.gameKnown)
        library.detected = "gta-v"
        assertTrue(library.gameKnown)
        assertEquals("Grand Theft Auto V", library.scopeTitle)
    }

    /** Oyun belliyken yalnız onun paketi aranır: aynı satır başka oyunda başka çevrilmiş olabilir. */
    @Test
    fun detectedGameUsesOnlyItsOwnPack() {
        val library = GamePackLibrary({
            listOf(pack("a-game", "A Game", "Come on.", "Hadi."), pack("b-game", "B Game", "Come on.", "Gel hadi."), pack("c-game", "C Game", "Get down!", "Yere yat!"))
        }, null)
        assertEquals("Gel hadi.", library.find("Come on."))
        library.detected = "a-game"
        assertEquals(PackMatch("Hadi.", "a-game"), library.findWithPack("Come on."))
        assertNull(library.find("Get down!"))
        // Kapatılan paket oyun belli olsa da kullanılmaz; arama yeniden bütün paketlerde yapılır.
        library.setDisabled(setOf("a-game"))
        assertFalse(library.gameKnown)
        assertEquals("Gel hadi.", library.find("Come on."))
    }
}

/** Oynanan oyunun paketi belli olmadan paket kullanılmaz (Windows: PackGateTests). */
class PackGateTest {
    private val hit = PackMatch("çeviri")

    @Test
    fun shortGenericLinesFromAnotherGameAreNotUsed() {
        var time = 0L
        val gate = PackGate { time }
        for (line in listOf("Miles?", "Spider-Man: Yeah,", "Nope...", "Class?!", "Leave me alone!")) {
            assertNull(gate.filter(line, hit))
            time += 20_000
        }
        assertFalse(gate.isOpen)
        assertNull(gate.filter("No match at all in any pack here.", null))
    }

    @Test
    fun opensAfterSeveralLongLinesAndThenUsesShortOnesToo() {
        var time = 0L
        val gate = PackGate { time }
        assertNull(gate.filter("We need to get out of here, now.", hit))
        // Aynı satır yeniden sorulunca sayılmaz.
        assertNull(gate.filter("We need to get out of here, now.", hit))
        time += 5_000
        assertNull(gate.filter("I told you not to come back.", hit))
        time += 5_000
        assertSame(hit, gate.filter("You should have listened to me, boy.", hit))
        assertTrue(gate.isOpen)
        assertSame(hit, gate.filter("Yeah.", hit))
    }

    @Test
    fun coincidencesSpreadOverALongTimeDoNotOpenIt() {
        var time = 0L
        val gate = PackGate { time }
        for (line in listOf("What are you doing out here?", "I have no idea what happened.", "We should get back to work.")) {
            assertNull(gate.filter(line, hit))
            time += PackGate.WINDOW_MS
        }
        assertFalse(gate.isOpen)
    }

    @Test
    fun closesWhenLongMatchesStop() {
        var time = 0L
        val gate = PackGate { time }
        gate.filter("We need to get out of here, now.", hit)
        gate.filter("I told you not to come back.", hit)
        gate.filter("You should have listened to me, boy.", hit)
        assertTrue(gate.isOpen)
        time += PackGate.CLOSE_AFTER_MS + 1
        assertFalse(gate.isOpen)
        assertNull(gate.filter("Yeah.", hit))
    }
}
