// SPDX-License-Identifier: AGPL-3.0-only
package com.gametranslator.gametranslator_mobile.xbox

import com.gametranslator.gametranslator_mobile.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CouchSubs'tan sonradan alınan Xbox düzeltmeleri ve kapanma nedeninin günlüğe yazılması. */
class XboxStreamFixesTest {
    // --- Kare onayı (Xbox onay gelmezse yayını ~40 sn'de kapatır) ---

    @Test
    fun frameAckPacketHasThePlayersLayout() {
        val packet = XboxChannels.frameAckPacket(7, 1234.0, listOf(XboxFrameAck(90_000, 10, 25), XboxFrameAck(93_000, 43, 58)))
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(14 + 1 + 28 * 2, packet.size)
        assertEquals(1, buffer.short.toInt())          // rapor türü: Metadata
        assertEquals(7, buffer.int)                    // sıra no
        assertEquals(1234.0, buffer.double, 0.0)
        assertEquals(2, buffer.get().toInt())          // kare sayısı
        assertEquals(90_000, buffer.int)               // serverDataKey
        assertEquals(10, buffer.int)                   // varış
        assertEquals(10, buffer.int)
        assertEquals(25, buffer.int)                   // çözülme
        assertEquals(25, buffer.int)
        assertEquals(1234, buffer.int)                 // paketin yazıldığı an
        assertEquals(1234, buffer.int)
        assertEquals(93_000, buffer.int)
    }

    @Test
    fun ackQueueDrainsInOrderAndKeepsTheNewestWhenFull() {
        val queue = XboxFrameAckQueue(capacity = 3)
        for (key in 1L..5L) queue.offer(XboxFrameAck(key, 0, 0))
        assertEquals(listOf(3L, 4L, 5L), queue.drain().map { it.serverDataKey })
        assertTrue(queue.drain().isEmpty())
        val many = XboxFrameAckQueue()
        repeat(40) { many.offer(XboxFrameAck(it.toLong(), 0, 0)) }
        assertEquals(XboxChannels.MAX_FRAME_ACKS, many.drain().size)
        assertEquals(40 - XboxChannels.MAX_FRAME_ACKS, many.drain().size)
        // RTP damgası 90 kHz, u32'ye sarılır
        assertEquals(90_000L, XboxFrameAckQueue.serverDataKey(1000))
        assertEquals((50_000_000L * 90) and 0xFFFFFFFFL, XboxFrameAckQueue.serverDataKey(50_000_000))
    }

    // --- Yeniden deneme: eskiden her 5 sn'de bir sonsuza dek yeniden bağlanıyordu ---

    @Test
    fun retriesBackOffAndStopAfterThree() {
        val machine = XboxSessionMachine()
        val delays = (1..4).map { (machine.fail(XboxError.NETWORK, "koptu") as XboxSessionMachine.Action.Retry).delayMs }
        assertEquals(listOf(5000L, 15000L, 45000L, null), delays)
        assertFalse((machine.phase as XboxSessionMachine.Phase.Failed).willRetry)
    }

    @Test
    fun aStreamThatStayedUpStartsANewRetrySeries() {
        var now = 1000L
        val machine = XboxSessionMachine(clock = { now })
        machine.fail(XboxError.NETWORK, "koptu")
        machine.fail(XboxError.NETWORK, "koptu")
        machine.start(); machine.played(); machine.status("Provisioned", null)
        assertTrue(machine.streaming())
        now += 61_000
        assertEquals(5000L, (machine.fail(XboxError.NETWORK, "koptu") as XboxSessionMachine.Action.Retry).delayMs)
    }

    @Test
    fun consoleRefusalIsNotRetried() {
        val machine = XboxSessionMachine()
        machine.start(); machine.played()
        val action = machine.status("Failed", """{"code":"WNSError","message":"console busy"}""")
        assertNull((action as XboxSessionMachine.Action.Retry).delayMs)
        assertFalse(XboxSessionMachine.hasErrorDetails("{}"))
        assertFalse(XboxSessionMachine.hasErrorDetails("""{"code":null,"message":""}"""))
        assertTrue(XboxSessionMachine.hasErrorDetails("""{"code":"X"}"""))
    }

    @Test
    fun frameCounterIgnoresFramesOfAClosedConnection() {
        val counter = XboxFrameCounter()
        val first = counter.beginSession()
        assertEquals(1L, counter.recordFrame(first))
        val second = counter.beginSession()
        assertNull(counter.recordFrame(first))            // eski bağlantının geç gelen karesi
        assertEquals(1L, counter.recordFrame(second))     // yeni bağlantının ilk karesi yine "ilk kare"
        assertEquals(2L, counter.totalFrames)
        counter.endSession(second)
        assertEquals(0L, counter.framesInSession(second))
    }

    // --- Kare dönüştürme: satır dolgusu (stride > genişlik) ve tek sayılı ölçüler taşmamalı ---

    @Test
    fun i420ConversionHandlesRowPaddingAndOddSizes() {
        for ((width, height, pad) in listOf(Triple(1920, 1080, 0), Triple(1280, 720, 64), Triple(853, 479, 11), Triple(2, 2, 0), Triple(1, 1, 3))) {
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val strideY = width + pad
            val strideC = chromaWidth + pad
            // Son satırda dolgu yok (libwebrtc'nin verdiği arabellek tam bu kadar olabilir)
            val y = ByteBuffer.allocateDirect(strideY * (height - 1) + width)
            val u = ByteBuffer.allocateDirect(strideC * (chromaHeight - 1) + chromaWidth)
            val v = ByteBuffer.allocateDirect(strideC * (chromaHeight - 1) + chromaWidth)
            while (y.hasRemaining()) y.put(235.toByte())
            while (u.hasRemaining()) u.put(128.toByte())
            while (v.hasRemaining()) v.put(128.toByte())
            y.rewind(); u.rewind(); v.rewind()
            val out = IntArray(width * height)
            XboxFrames.i420ToArgb(y, u, v, strideY, strideC, strideC, width, height, out)
            assertEquals("$width x $height", 0xFFFFFFFF.toInt(), out[0])
            assertEquals("$width x $height", 0xFFFFFFFF.toInt(), out[out.size - 1])
            assertEquals(0, y.position())
        }
    }

    // --- Kapanma nedeni ---

    @Test
    fun exitReasonsAreNamedAndOrdinaryExitsAreNotReported() {
        assertTrue(CrashLog.classify(4).second)     // Java hatası
        assertTrue(CrashLog.classify(5).second)     // yerel çökme
        assertTrue(CrashLog.classify(3).second)     // bellek
        assertTrue(CrashLog.classify(6).second)     // ANR
        assertTrue(CrashLog.classify(5).first.contains("yerel"))
        for (ordinary in listOf(1, 2, 10, 11, 13)) assertFalse("neden $ordinary", CrashLog.classify(ordinary).second)
    }

    @Test
    fun crashTextNamesTheThreadTheErrorAndItsCause() {
        val error = IllegalStateException("kare bozuk", java.nio.BufferUnderflowException())
        val text = CrashLog.describe("DecodingQueue", error, 0L)
        assertTrue(text.lines()[0].endsWith("iş parçacığı DecodingQueue"))
        assertTrue(text.contains("java.lang.IllegalStateException: kare bozuk"))
        assertTrue(text.contains("neden: java.nio.BufferUnderflowException"))
        assertTrue(text.contains("XboxStreamFixesTest"))
        assertTrue(text.lines().size < 45)
    }

    // --- Yayın derlemesi: küçültme WebRTC'nin adıyla aradığı sınıfları silmemeli ---

    /**
     * 2026-11-17: yayın derlemesinde Xbox oturumu açılırken uygulama kapanıyordu. WebRTC paketi org.webrtc'nin yanında
     * org.jni_zero'yu da getirir; kitaplık açılırken org.jni_zero.JniInit'i adıyla arar. Kural yalnız org.webrtc'yi
     * koruyordu, R8 org.jni_zero'yu siliyordu.
     */
    @Test
    fun shrinkerKeepsEveryPackageTheWebRtcLibraryLooksUpByName() {
        val rules = listOf("proguard-rules.pro", "app/proguard-rules.pro", "android/app/proguard-rules.pro")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(rules.contains("-keep class org.webrtc.** { *; }"))
        assertTrue(rules.contains("-keep class org.jni_zero.** { *; }"))
    }

    @Test
    fun softwareDecoderIsOnlyChosenWhenTheCrashWasInTheDecoder() {
        // Kitaplık yüklenirken çökme (gerçek kullanıcı dökümü): çözücüyle ilgisi yok
        assertFalse(CrashLog.inVideoDecoder(listOf("sinyal: SIGTRAP (TRAP_BRKPT)", "iş parçacığı: gt-xbox",
            "  #00 base.apk!libjingle_peerconnection_so.so +0x3a2e48", "  #04 base.apk!libjingle_peerconnection_so.so JNI_OnLoad",
            "  #10 boot.oat java.lang.System.loadLibrary")))
        assertTrue(CrashLog.inVideoDecoder(listOf("sinyal: SIGSEGV", "iş parçacığı: DecodingQueue", "  #00 libc.so memcpy")))
        assertTrue(CrashLog.inVideoDecoder(listOf("iş parçacığı: AndroidVideoDec", "  #01 libstagefright.so android::MediaCodec::onMessageReceived")))
        assertFalse(CrashLog.inVideoDecoder(emptyList()))
    }

    // --- Çözücü sarmalayıcısı: libwebrtc yerel koddan çağırır, dışarı çıkan her Java hatası süreci durdurur ---

    private class FakeDecoder(var fail: Boolean = false) : org.webrtc.VideoDecoder {
        var decoded = 0
        var lastInfoWasNull = false
        var callback: org.webrtc.VideoDecoder.Callback? = null
        override fun initDecode(settings: org.webrtc.VideoDecoder.Settings?, callback: org.webrtc.VideoDecoder.Callback?): org.webrtc.VideoCodecStatus {
            this.callback = callback
            return org.webrtc.VideoCodecStatus.OK
        }
        override fun release(): org.webrtc.VideoCodecStatus = org.webrtc.VideoCodecStatus.OK
        override fun decode(frame: org.webrtc.EncodedImage?, info: org.webrtc.VideoDecoder.DecodeInfo?): org.webrtc.VideoCodecStatus {
            if (fail) throw IllegalStateException("codec is released")
            decoded++
            lastInfoWasNull = info == null
            return org.webrtc.VideoCodecStatus.OK
        }
        override fun getImplementationName(): String = "fake.avc.decoder"
    }

    private fun image(captureNs: Long) = org.webrtc.EncodedImage.builder()
        .setBuffer(ByteBuffer.allocateDirect(4), null).setCaptureTimeNs(captureNs).createEncodedImage()

    /**
     * 2026-11-17, Blackview Tab 20: Xbox görüntüsü gelir gelmez uygulama kapanıyordu ("DecodingQueue", SIGABRT).
     * libwebrtc decode'u ikinci parametre null olarak çağırır; sarmalayıcı onu null olamaz diye tanımlıyordu.
     */
    @Test
    fun decoderWrapperAcceptsTheNullDecodeInfoThatWebRtcPasses() {
        val inner = FakeDecoder()
        val wrapper: org.webrtc.VideoDecoder = ReportingDecoder(inner, { _, _, _ -> }, clock = { 5L })
        // Yerel kodun yaptığı çağrı: arayüz üzerinden, bilgi null
        val decode = org.webrtc.VideoDecoder::class.java.getMethod("decode", org.webrtc.EncodedImage::class.java, org.webrtc.VideoDecoder.DecodeInfo::class.java)
        assertEquals(org.webrtc.VideoCodecStatus.OK, decode.invoke(wrapper, image(1_000_000), null))
        assertEquals(1, inner.decoded)
        assertTrue(inner.lastInfoWasNull)
        assertEquals("fake.avc.decoder", wrapper.implementationName)
    }

    @Test
    fun decoderErrorsBecomeAStatusCodeAndAreReportedOnce() {
        val inner = FakeDecoder(fail = true)
        val errors = ArrayList<String>()
        val wrapper: org.webrtc.VideoDecoder = ReportingDecoder(inner, { _, _, _ -> }, { errors += it }, clock = { 5L })
        assertEquals(org.webrtc.VideoCodecStatus.ERROR, wrapper.decode(image(1), null))
        assertEquals(org.webrtc.VideoCodecStatus.ERROR, wrapper.decode(image(2), null))
        assertEquals(1, errors.size)
        assertTrue(errors[0], errors[0].contains("java.lang.IllegalStateException: codec is released") && errors[0].contains("fake.avc.decoder"))
    }

    @Test
    fun decodedFramesAreReportedWithArrivalAndDecodeTimes() {
        var now = 100L
        val seen = ArrayList<Triple<Long, Long, Long>>()
        val inner = FakeDecoder()
        val wrapper: org.webrtc.VideoDecoder = ReportingDecoder(inner, { rtpMs, arrived, decoded -> seen += Triple(rtpMs, arrived, decoded) }, clock = { now })
        var delivered = 0
        wrapper.initDecode(null) { _, _, _ -> delivered++ }
        wrapper.decode(image(7_000_000), null)
        now = 130L
        val buffer = org.webrtc.JavaI420Buffer.wrap(2, 2, ByteBuffer.allocateDirect(4), 2, ByteBuffer.allocateDirect(1), 1, ByteBuffer.allocateDirect(1), 1, null)
        val frame = org.webrtc.VideoFrame(buffer, 0, 7_000_000)
        inner.callback!!.onDecodedFrame(frame, 30, null)
        assertEquals(listOf(Triple(7L, 100L, 130L)), seen)
        assertEquals(1, delivered)
        frame.release()
    }
}
