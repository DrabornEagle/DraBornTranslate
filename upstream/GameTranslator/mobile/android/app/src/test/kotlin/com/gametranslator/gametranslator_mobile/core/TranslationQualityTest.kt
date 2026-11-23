// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bölünen cümleler ve konuşmacı adları (Windows: ContinuationTranslatorTests). */
class ContinuationTranslatorTest {
    /** Satırları sırayla çevirir; (çevirmene giden metinler, dönen çeviriler). */
    private fun feed(vararg lines: Pair<String, Long>): Pair<List<String>, List<String>> {
        var time = 0L
        val sent = mutableListOf<String>()
        val translator = ContinuationTranslator({ text -> sent.add(text); "tr:$text" }, clock = { time })
        val shown = mutableListOf<String>()
        for ((text, at) in lines) {
            time = at
            shown.add(translator.translate(text)!!)
        }
        return sent to shown
    }

    @Test
    fun speakerNameIsKeptAsItIsAndOnlyTheSpeechIsTranslated() {
        val (sent, shown) = feed("ANNA: Are you all right?" to 0L, "Careful now." to 5000L)
        assertEquals(listOf("Are you all right?", "Careful now."), sent)
        assertEquals(listOf("ANNA: tr:Are you all right?", "tr:Careful now."), shown)
    }

    @Test
    fun speakerNameReadTwiceIsNotPartOfTheSpeech() {
        for ((line, speech) in listOf(
            "Faye: Faye: This way." to "This way.",
            "Kratos: Kratos. The tracks stop here." to "The tracks stop here.",
            "Jesse: \"Jesse: Sounds exciting." to "Sounds exciting.",
            "Lae'zel: Lae'ze: It cannot be helped. Come." to "It cannot be helped. Come.",
            "Faye: S This way." to "This way.",
            "CAL • Think BD wants you to knock that down." to "Think BD wants you to knock that down.",
            "BODE ·- Now I'm taking orders from a droid?" to "Now I'm taking orders from a droid?",
            "Faye: Faye:S This way." to "This way.",
            "Varl: Varl• So the storms." to "So the storms.",
            "Woman's Voice: Woman's Voice The Zero Dawn system." to "The Zero Dawn system.",
            "Aloy: \" —Aloy: We'll need to cut through." to "We'll need to cut through.",
            "Sam: Sam, wait for me!" to "Sam, wait for me!",
            "Sam: Samuel is gone." to "Samuel is gone.",
        )) assertEquals(line, listOf(speech), feed(line to 0L).first)
    }

    @Test
    fun misreadColonAndMisreadSpeakerNamesUseTheKnownName() {
        val lines = (0 until 4).map { "Stints: Line number $it it is." to it * 20000L }.toMutableList()
        lines.add("Stintsy Fuck, he's here." to 100000L)
        lines.add("Jockie Hey, pal." to 120000L)
        lines.add("Stints is a cop." to 140000L)
        val (_, shown) = feed(*lines.toTypedArray())
        assertEquals("Stints: tr:Fuck, he's here.", shown[4])
        assertEquals("tr:Jockie Hey, pal.", shown[5])
        assertEquals("tr:Stints is a cop.", shown[6])

        val mimir = (0 until 4).map { "Mimir: Line number $it it is." to it * 20000L } +
            listOf("Minnir: He never came back." to 100000L, "QHI: Mimir: It's been some time..." to 120000L)
        val (sent, said) = feed(*mimir.toTypedArray())
        assertEquals("Mimir: tr:He never came back.", said[4])
        assertEquals("It's been some time...", sent.last())
        assertEquals("Mimir: tr:It's been some time...", said.last())
    }

    @Test
    fun severalSpeakersAreTranslatedSeparatelyWithTheirNames() {
        val (sent, shown) = feed("DUKE: I cannot believe my eyes!\nYou gave us a scare, brother.\nDAMIR: Move, Duke!" to 0L)
        assertEquals(listOf("I cannot believe my eyes! You gave us a scare, brother.", "Move, Duke!"), sent.sorted())
        assertEquals("DUKE: tr:I cannot believe my eyes! You gave us a scare, brother.\nDAMIR: tr:Move, Duke!", shown[0])
        assertEquals("Mimir: tr:It's been some time...", feed("Mimir: It's been some time...\nKratos:" to 0L).second[0])
    }

    @Test
    fun sentenceSplitOverSeveralSubtitlesIsTranslatedWhole() {
        val (sent, shown) = feed(
            "Marko: You are the ones" to 0L,
            "Marko: who are going to need help..." to 2000L,
            "Marko: when they come for you." to 5000L,
            "Elena: Come for us?" to 9000L)
        assertEquals(listOf(
            "You are the ones",
            "You are the ones who are going to need help...",
            "You are the ones who are going to need help... when they come for you.",
            "Come for us?"), sent)
        assertEquals("Marko: tr:You are the ones who are going to need help... when they come for you.", shown[2])
        assertEquals("Elena: tr:Come for us?", shown[3])
        assertEquals("Kraven: tr:You grow slow in your old age, old friend.",
            feed("Kraven: You grow slow in your old age," to 0L, "old friend." to 2500L).second[1])
    }

    @Test
    fun finishedSentencesAnotherSpeakerAndLateLinesStaySeparate() {
        val (sent, _) = feed(
            "Sandman: Leave me alone!" to 0L,
            "Sandman: I just wanted a regular life!" to 2000L,
            "Sandman: You care about them--" to 4000L,
            "Elena: Of course I do." to 6000L,
            "Rio: I am heading downtown to" to 8000L,
            "Rio: help coordinate supplies." to 8000L + ContinuationTranslator.WITHIN_MS + 1)
        assertEquals(listOf("Leave me alone!", "I just wanted a regular life!", "You care about them--", "Of course I do.",
            "I am heading downtown to", "help coordinate supplies."), sent)
    }

    @Test
    fun newSentenceAfterAnEllipsisPartialReadsAndRereadsAreNotJoined() {
        val (sent, _) = feed(
            "ANNA: At least it held..." to 0L,
            "ANNA: Well, I would say we are out of the woods." to 3000L,
            "ANNA: Got another" to 9000L,
            "ANNA: We sure gave them a fight!" to 11000L,
            "PRESS X TO CONTINUE" to 20000L,
            "LEVEL 2" to 22000L)
        assertEquals("Well, I would say we are out of the woods.", sent[1])
        assertEquals("We sure gave them a fight!", sent[3])
        assertEquals("LEVEL 2", sent[5])
        val (again, _) = feed("Marko: I havent been okay" to 0L, "Marko: I haven't been okay" to 500L, "Marko: for a long time--" to 2500L)
        assertEquals("I haven't been okay", again[1])
        assertEquals("I havent been okay for a long time--", again[2])
        val (cut, _) = feed("DUKE: Colonel, Sir! We have a situation here..." to 0L, "r and Hit Indicators are active by default." to 600L)
        assertEquals("r and Hit Indicators are active by default.", cut.last())
    }

    @Test
    fun openSentenceJoinsACapitalizedContinuationAndMisreadSpeaker() {
        val (sent, _) = feed(
            "Priestess: The carvings say the spear was hidden in\nthe caves beneath" to 0L,
            "Priestess: Kephallonia when the temple was built!" to 3000L)
        assertEquals("The carvings say the spear was hidden in the caves beneath Kephallonia when the temple was built!", sent[1])
        val (marta, shown) = feed("Marta: Okay," to 0L, "arta: the gate is open now!" to 1500L)
        assertEquals("Okay, the gate is open now!", marta[1])
        assertEquals("Marta: tr:Okay, the gate is open now!", shown[1])
    }

    @Test
    fun repeatedLinesInOneReadingCollapse() {
        assertEquals("Kraven: A fate you will not share.",
            ContinuationTranslator.withoutRepeatedLines("Kraven: A fate yowwill not share.\nKraven: A fate you will not share."))
        assertEquals("Where are you going?\n- Home.", ContinuationTranslator.withoutRepeatedLines("Where are you going?\n- Home."))
        assertEquals("One line.", ContinuationTranslator.withoutRepeatedLines(" One line. "))
        assertEquals("Take the north road.\nThen the south road.", ContinuationTranslator.withoutRepeatedLines("Take the north road.\nThen the south road."))
    }

    @Test
    fun capsNameFixesLowercaseI() {
        for ((read, expected) in listOf("DAMiR" to "DAMIR", "SOLDiER" to "SOLDIER", "GAETANo" to "GAETANO", "ENzo" to "ENZO",
            "McKay" to "McKay", "Young ALEXiOS" to "Young ALEXIOS", "Mimir" to "Mimir", "Ri" to "Ri"))
            assertEquals(expected, ContinuationTranslator.capsName(read))
    }

    @Test
    fun phrasebookAndRadioSignOff() {
        val seen = mutableListOf<String>()
        val translator = ContinuationTranslator({ text -> seen.add(text); "tr:$text" }, phrasebook = ShortPhrases::translate)
        assertEquals("Mimir: Ee?", translator.translate("Mimir: Well?"))
        assertTrue(seen.isEmpty())
        assertEquals("OFFICER: tr:Base, we apprehended the engineer! Tamam!", translator.translate("OFFICER: Base, we apprehended the engineer! Over!"))
        assertEquals("Base, we apprehended the engineer!", seen.last())
    }

    @Test
    fun failedTranslationIsNull() {
        assertNull(ContinuationTranslator({ null }).translate("Where are you going?"))
        assertNull(ContinuationTranslator({ null }).translate("DUKE: Go!\nDAMIR: Move!"))
    }
}

/** Kısa ünlemler (Windows: ShortPhrasesTests). */
class ShortPhrasesTest {
    @Test
    fun knownShortLinesHaveFixedTranslations() {
        for ((source, expected) in listOf("Well?" to "Ee?", "See?" to "Gördün mü?", "Brother?" to "Kardeşim?", "Hurry! Please!" to "Acele et! Lütfen!",
            "Okay." to "Tamam.", "Morning!" to "Günaydın!", "No..." to "Hayır...", "...Yeah." to "...Evet.", "Let's go." to "Hadi gidelim.",
            "Let’s go!" to "Hadi gidelim!", "Roger that!" to "Anlaşıldı!"))
            assertEquals(expected, ShortPhrases.translate(source))
    }

    @Test
    fun otherLinesAreLeftToTheTranslator() {
        for (source in listOf("See? We're rich.", "Behind you, brother!", "Well, I think we should go now and find him.", "Faye?"))
            assertNull(ShortPhrases.translate(source))
    }

    @Test
    fun shoutingTranslationOfAQuietLineIsLowered() {
        assertEquals("Hayır...", ShortPhrases.fixShouting("No...", "HAYIR..."))
        assertEquals("Nerede?", ShortPhrases.fixShouting("Where is it?", "NEREDE?"))
        assertEquals("DUR!", ShortPhrases.fixShouting("STOP!", "DUR!"))
        assertEquals("Bekle.", ShortPhrases.fixShouting("Wait.", "Bekle."))
        assertEquals("NCPD mi?", ShortPhrases.fixShouting("Is it the NCPD?", "NCPD mi?"))
    }
}

/** Oyun deyimleri (Windows: GameEnglishTests). */
class GameEnglishTest {
    @Test
    fun gameIdiomsBecomePlainEnglishAndOtherTextIsUnchanged() {
        for ((source, plain) in listOf(
            "Hold your fire! They're friendlies!" to "Hold your fire! They're allies!",
            "I got your six." to "I've got your back.",
            "Check your six!" to "Watch behind you!",
            "Heads up! Incoming!" to "Watch out! Incoming!",
            "Took you long enough." to "You're late.",
            "Careful, we've got company." to "Careful, enemies are coming.",
            "We're sitting ducks out here." to "We're easy targets out here.",
            "Copy that. Moving in." to "Copy that. Going in.",
        )) assertEquals(plain, GameEnglish.plain(source))
        for (source in listOf("The friendliest town in the north.", "Six of them, behind the gate.", "Stand down!"))
            assertEquals(source, GameEnglish.plain(source))
    }
}

/** Türkçe düzeltmeler ve cihaz üstü çevirinin metin hattı (Windows: TurkishPostEditTests, FreeTranslationTests). */
class FreeTranslationTest {
    @Test
    fun sourceEndingIsKept() {
        for ((source, translation, expected) in listOf(
            Triple("Run!", "Koş.", "Koş!"), Triple("Run!", "Koş", "Koş!"), Triple("What?!", "Ne?", "Ne?!"),
            Triple("Wait…", "Bekle.", "Bekle…"), Triple("Wait...", "Bekle.", "Bekle..."), Triple("Where is he?", "O nerede.", "O nerede?"),
            Triple("He said \"Run!\"", "\"Koş.\" dedi.", "\"Koş.\" dedi."), Triple("\"Run!\"", "\"Koş.\"", "\"Koş!\""),
            Triple("We go.", "Gidiyoruz!", "Gidiyoruz!"), Triple("Boy!", "Evlat!", "Evlat!"),
        )) assertEquals(source, expected, TurkishPostEdit.apply(source, translation))
    }

    @Test
    fun spacesAndQuotesFollowTheRules() {
        assertEquals("Dur, bekle!", TurkishPostEdit.apply("Okay. Go.", "Dur , bekle !"))
        assertEquals("Ne oldu?", TurkishPostEdit.apply("Okay. Go.", "Ne  oldu ?"))
        assertEquals("Peki... gidelim.", TurkishPostEdit.apply("Okay. Go.", "Peki ... gidelim ."))
        assertEquals("\"Koş\" dedi, Kratos'un oğlu.", TurkishPostEdit.apply("He said \"run\", Kratos's son.", "“Koş” dedi, Kratos’un oğlu."))
        assertEquals("“Koş” dedi, Kratos’un oğlu.", TurkishPostEdit.apply("He said “run”, Kratos’s son.", "\"Koş\" dedi, Kratos'un oğlu."))
    }

    @Test
    fun imperativesFollowAnInformalLine() {
        assertEquals(listOf("Sana söyledim.", "Bekle!", "Dikkat et, evlat."),
            TurkishPostEdit.register(listOf("Sana söyledim.", "Bekleyin!", "Dikkat edin, evlat.")))
        assertEquals(listOf("Seni gördüm. Hemen gel!"), TurkishPostEdit.register(listOf("Seni gördüm. Hemen gelin!")))
        for (sentence in listOf("Bekleyin!", "Siz bekleyin, sana sonra bakarım!", "Sen gelin misin?"))
            assertEquals(listOf(sentence), TurkishPostEdit.register(listOf(sentence)))
        assertEquals(listOf(null, "Sana söyledim.", null, "Dur!"), TurkishPostEdit.register(listOf(null, "Sana söyledim.", null, "Durun!")))
    }

    @Test
    fun separatorAndShortLines() {
        assertTrue(FreeTranslation.isShort("Not you, boy."))
        assertTrue(!FreeTranslation.isShort("We go to the mountain.") && !FreeTranslation.isShort("..."))
        assertEquals("Sen değil.", FreeTranslation.afterSeparator("Annen nerede? *** Sen değil.", "Not you."))
        assertNull(FreeTranslation.afterSeparator("Annen nerede? Sen değil.", "Not you."))
        assertNull(FreeTranslation.afterSeparator("Annen *** nerede? *** Sen değil.", "Not you."))
        assertNull(FreeTranslation.afterSeparator("Annen nerede? **** Sen değil.", "Not you."))
    }

    private val gow = Glossary.of(mapOf("Leviathan Axe" to "Leviathan Baltası"), listOf("Kratos", "Atreus"))

    @Test
    fun termsAreProtectedAndRestored() {
        val calls = mutableListOf<String>()
        val result = FreeTranslation.translate("Kratos, throw the Leviathan Axe!", null, gow, { it }) { input ->
            calls.add(input)
            "Zarmon, Velkor'u fırlat."
        }
        assertEquals(listOf("Zarmon, throw the Velkor!"), calls)
        assertEquals("Kratos, Leviathan Baltası'nı fırlat!", result)
    }

    @Test
    fun lostPlaceholderFallsBackAndGameEnglishRunsFirst() {
        val calls = mutableListOf<String>()
        val result = FreeTranslation.translate("Throw the Leviathan Axe!", null, gow, { it }) { input ->
            calls.add(input)
            if (input.contains("Zarmon")) "Baltayı fırlat!" else "Leviathan Baltasını fırlat!"
        }
        assertEquals(listOf("Throw the Zarmon!", "Throw the Leviathan Axe!"), calls)
        assertEquals("Leviathan Baltasını fırlat!", result)
        var sent: String? = null
        FreeTranslation.translate("HEADS UP, KRATOS!", null, gow, { if (it == "HEADS UP, KRATOS!") "Heads up, kratos!" else it }) { input ->
            sent = input
            "Dikkat et, Zarmon!"
        }
        assertEquals("Watch out, Zarmon!", sent)
    }

    @Test
    fun shortLineIsTranslatedWithItsContext() {
        val calls = mutableListOf<String>()
        val result = FreeTranslation.translate("Not you.", "Where is your mother?", gow, { it }) { input ->
            calls.add(input)
            if (input.contains("***")) "Annen nerede, sen değil." else "Sen değil."
        }
        assertEquals(listOf("Where is your mother? *** Not you.", "Not you."), calls)
        assertEquals("Sen değil.", result)
    }

    /** Cihaz üstü çeviri: cümle cümle, konuşmacı etiketi çevrilmez, hitap satırın bütününe göre düzelir. */
    @Test
    fun freeTranslatorTranslatesSentenceBySentence() {
        val seen = mutableListOf<String>()
        val table = mapOf("I told you." to "Sana söyledim.", "Wait!" to "Bekleyin.", "Watch out!" to "Dikkat et!")
        val translator = FreeTranslator({ text -> seen.add(text); table[text] ?: "?" })
        assertEquals("ATREUS: Sana söyledim. Bekle!", translator.translate("ATREUS: I told you. Wait!"))
        assertEquals(listOf("I told you.", "Wait!"), seen)
        assertEquals("Dikkat et!", translator.translate("Heads up!"))
        assertEquals("Watch out!", seen.last())
        assertNull(FreeTranslator({ null }).translate("Wait!"))
    }
}
