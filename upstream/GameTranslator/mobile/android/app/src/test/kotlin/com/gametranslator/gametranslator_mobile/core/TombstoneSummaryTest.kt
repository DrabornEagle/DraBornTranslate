// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Yerel çökme dökümünden günlüğe yazılan özet. Döküm burada tombstone.proto alan numaralarıyla elle kurulur. */
class TombstoneSummaryTest {
    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v and 0x7fL.inv() == 0L) { out.write(v.toInt()); break }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    private fun number(field: Int, value: Long) = varint((field.toLong() shl 3) or 0) + varint(value)
    private fun bytes(field: Int, value: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun text(field: Int, value: String) = bytes(field, value.toByteArray())

    private fun frame(pc: Long, function: String?, file: String) =
        number(1, pc) + (function?.let { text(4, it) } ?: ByteArray(0)) + text(6, file) + text(8, "abcdef0123")

    private fun thread(id: Long, name: String, vararg frames: ByteArray) =
        number(1, id) + text(2, name) + frames.fold(ByteArray(0)) { all, f -> all + bytes(4, f) }

    private fun tombstone(): ByteArray =
        text(2, "samsung/gts9/gts9:14/UP1A/1:user/release-keys") +
            number(5, 4321) + number(6, 77) +
            bytes(10, number(1, 11) + text(2, "SIGSEGV") + number(3, 1) + text(4, "SEGV_MAPERR")) +
            // başka bir iş parçacığı: yazılmamalı
            bytes(16, number(1, 12) + bytes(2, thread(12, "main", frame(0x10, "epoll_wait", "/apex/com.android.runtime/lib64/bionic/libc.so")))) +
            bytes(16, number(1, 77) + bytes(2, thread(77, "DecodingQueue",
                frame(0x4a2f0, "memcpy", "/apex/com.android.runtime/lib64/bionic/libc.so"),
                frame(0x8812c4, "webrtc::jni::CopyPlane(unsigned char const*, int)", "/data/app/~~x==/com.gametranslator-y==/lib/arm64/libjingle_peerconnection_so.so"),
                frame(0x99aa, null, "/data/app/~~x==/com.gametranslator-y==/lib/arm64/libjingle_peerconnection_so.so"))))

    @Test
    fun namesTheSignalTheCrashingThreadAndItsTopFrames() {
        val lines = TombstoneSummary.summarize(tombstone())
        assertEquals("sinyal: SIGSEGV (SEGV_MAPERR)", lines[0])
        assertEquals("iş parçacığı: DecodingQueue", lines[1])
        assertEquals("  #00 libc.so memcpy", lines[2])
        assertEquals("  #01 libjingle_peerconnection_so.so webrtc::jni::CopyPlane(unsigned char const*, int)", lines[3])
        assertEquals("  #02 libjingle_peerconnection_so.so +0x99aa", lines[4])
        assertEquals(5, lines.size)
        assertTrue(lines.none { it.contains("epoll_wait") })
    }

    @Test
    fun abortMessageIsShown() {
        val data = number(6, 5) + bytes(10, text(2, "SIGABRT")) + text(14, "Check failed: buffer.capacity() >= size") +
            bytes(16, number(1, 5) + bytes(2, thread(5, "gt-xbox", frame(1, "abort", "/system/lib64/libc.so"))))
        val lines = TombstoneSummary.summarize(data)
        assertTrue(lines.contains("abort: Check failed: buffer.capacity() >= size"))
        assertTrue(lines.contains("  #00 libc.so abort"))
    }

    @Test
    fun unknownFormatFallsBackToLibraryNamesAndNeverThrows() {
        val garbage = byteArrayOf(0x07, 0x7f, 0x00, 0x13) + "xx /data/app/lib/arm64/libflutter.so yy".toByteArray() + byteArrayOf(0, 1, 2) +
            "SIGSEGV".toByteArray() + byteArrayOf(0x7f, 0x7f, 0x7f)
        val lines = TombstoneSummary.summarize(garbage)
        assertEquals("çökme dökümü (ham):", lines[0])
        assertTrue(lines.any { it.contains("libflutter.so") })
        assertTrue(lines.any { it.contains("SIGSEGV") })
        assertTrue(TombstoneSummary.summarize(ByteArray(0)).isEmpty())
        assertTrue(TombstoneSummary.summarize(byteArrayOf(0x0a, 0x7f)).isEmpty())   // yarım kalmış veri
    }

    private fun logMessage(tid: Long, priority: Long, tag: String, message: String) =
        text(1, "11-17 20:59:01.123") + number(2, 4321) + number(3, tid) + number(4, priority) + text(5, tag) + text(6, message)

    /** Yerel kitaplık bir Java hatası yüzünden durduğunda yığında yalnız "abort" görünür; hatanın adı günlük kayıtlarındadır. */
    @Test
    fun logLinesOfTheCrashingThreadAreShown() {
        val logs = bytes(2, logMessage(12, 4, "GameTranslator", "durum: streaming")) +
            bytes(2, logMessage(77, 5, "System.err", "java.lang.NullPointerException: Parameter specified as non-null is null: parameter info")) +
            bytes(2, logMessage(77, 5, "System.err", "	at com.gametranslator.gametranslator_mobile.xbox.ReportingDecoder.decode(Unknown Source:9)")) +
            bytes(2, logMessage(77, 7, "DEBUG", "pid: 4321, tid: 77"))
        val data = number(6, 77) + bytes(10, text(2, "SIGABRT")) +
            bytes(16, number(1, 77) + bytes(2, thread(77, "DecodingQueue", frame(1, "abort", "/system/lib64/libc.so")))) +
            bytes(18, text(1, "main") + logs)
        val lines = TombstoneSummary.summarize(data)
        val at = lines.indexOf("çökmeden önceki günlük:")
        assertTrue(at > 0)
        assertEquals("  > System.err: java.lang.NullPointerException: Parameter specified as non-null is null: parameter info", lines[at + 1])
        assertTrue(lines[at + 2].contains("ReportingDecoder.decode"))
        assertEquals(at + 3, lines.size)   // başka iş parçacığının bilgi satırı ve dökümün kendi çıktısı yok
    }
}
