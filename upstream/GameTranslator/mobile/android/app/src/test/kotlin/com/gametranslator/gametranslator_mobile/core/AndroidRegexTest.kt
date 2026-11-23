// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android'in düzenli ifade motoru (ICU) JVM'in bazı bayraklarını tanımaz: `(?U)` (UNICODE_CHARACTER_CLASS) telefonda
 * sınıf yüklenirken hata verir (IconWords hiç yüklenemiyor, ek alanların her turu düşüyordu), JVM'deki testler ise geçer.
 * Kaynakta bu bayrak kullanılmasın; `\w` yerine `[\p{L}\p{N}_]` yazılır.
 */
class AndroidRegexTest {
    @Test
    fun sourcesDoNotUseFlagsAndroidRejects() {
        val root = File("src/main/kotlin")
        assertTrue("kaynak klasörü bulunamadı: ${root.absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (line.contains("(?U)")) "${file.name}:${i + 1}" else null } }
            .toList()
        assertTrue("Android'in tanımadığı (?U) bayrağı: $offenders", offenders.isEmpty())
    }
}
