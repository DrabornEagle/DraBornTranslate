// SPDX-License-Identifier: AGPL-3.0-only
// Kare onayı: Windows'taki xbox-xcloud-player 0.2.11 (MIT, unknownskl) her gösterilen karede `input` kanalına bir
// Metadata kaydı yazar (Component/Video.ts `requestVideoFrameCallback` → Channel/Input.ts `addProcessedFrame`, 16 ms'de
// bir paket). Xbox bu onayları bekler: "Without sending any data back the stream will only last for +- 40 seconds. ...
// To maintain the stream, you have to send data back to the Xbox via the input channel. You have to confirm the packets
// back that you have processed." (Greenlight wiki, WebRTC). Telefon önceden yalnız ilk ClientMetadata paketini
// gönderiyordu; Xbox yayını kısa sürede kapatıp "uzaktan yürütmeyi durdurdu" diyordu, uygulama da baştan bağlanıyordu.
package com.gametranslator.gametranslator_mobile.xbox

import android.os.SystemClock
import org.webrtc.EglBase
import org.webrtc.EncodedImage
import org.webrtc.HardwareVideoDecoderFactory
import org.webrtc.PlatformSoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoDecoder
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoDecoderFallback
import java.util.concurrent.ConcurrentHashMap

/** Çözülen kare: RTP zaman damgasının milisaniyesi (damga / 90), çözücüye geliş ve çözülme anı (elapsedRealtime, ms). */
internal fun interface DecodedFrameListener {
    fun onDecoded(rtpMs: Long, arrivedAt: Long, decodedAt: Long)
}

/**
 * DefaultVideoDecoderFactory'nin aynısı (donanım çözücüsü, yoksa ya da bozulursa yazılım), yalnız Java çözücüleri
 * çözdükleri kareyi bildirir. libwebrtc karenin RTP zaman damgasını Java'ya yalnız buradan, çözücüye giden karenin
 * `captureTimeNs`'i olarak verir (sdk/android/src/jni/video_decoder_wrapper.cc: `capture_time_ms_ = RtpTimestamp() /
 * kNumRtpTicksPerMillisec`); görüntü alıcısına (VideoSink) giden karede ise gösterim zamanı vardır.
 */
internal class XboxDecoderFactory(
    private val listener: DecodedFrameListener,
    /** Çözücüde yakalanan hata (bir kez) buraya bildirilir; oturum günlüğe yazar. */
    private val onError: (String) -> Unit = {},
    /**
     * Donanım çözücüsü hiç kullanılmaz: bu cihazda önceki Xbox oturumu yerel çökmeyle bittiyse (bkz. CrashLog). Android'in
     * yazılım H.264 çözücüsü daha çok işlemci harcar ama üreticinin donanım çözücüsünden geçmez.
     */
    private val softwareOnly: Boolean = false,
) : VideoDecoderFactory {
    private val hardware = HardwareVideoDecoderFactory(null as EglBase.Context?)
    private val software = SoftwareVideoDecoderFactory()
    private val platform = PlatformSoftwareVideoDecoderFactory(null)

    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
        val hw = if (softwareOnly) null else hardware.createDecoder(info)?.let { ReportingDecoder(it, listener, onError) }
        // SoftwareVideoDecoderFactory'ninkiler yereldir (C++); onlardan kare bilgisi alınamaz.
        val sw = software.createDecoder(info) ?: platform.createDecoder(info)?.let { ReportingDecoder(it, listener, onError) }
        return if (hw != null && sw != null) VideoDecoderFallback(sw, hw) else hw ?: sw
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = LinkedHashSet<VideoCodecInfo>().apply {
        addAll(software.supportedCodecs)
        if (!softwareOnly) addAll(hardware.supportedCodecs)
        addAll(platform.supportedCodecs)
    }.toTypedArray()
}

/**
 * Java çözücüsünü sarar: girişte geliş anını, çıkışta kareyi bildirir; kareye dokunmaz.
 *
 * Bu sınıfın yöntemlerini libwebrtc yerel koddan (JNI) çağırır; buradan dışarı çıkan her Java hatası süreci durdurur
 * (jni_zero CheckException → abort). İki kural bu yüzden:
 *  - [decode]'un ikinci parametresi null gelebilir olmalı: libwebrtc onu hep null gönderir
 *    (video_decoder_wrapper.cc). Null olamaz yazılınca Kotlin'in parametre denetimi ilk karede hata fırlatıyor,
 *    Xbox görüntüsü gelir gelmez uygulama kapanıyordu (2026-11-17, "DecodingQueue" iş parçacığında SIGABRT).
 *  - Çözücünün fırlattığı hata yakalanır ve durum kodu olarak döner.
 */
internal class ReportingDecoder(
    private val inner: VideoDecoder,
    private val listener: DecodedFrameListener,
    private val onError: (String) -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : VideoDecoder {
    private val errorReported = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun failed(where: String, error: Throwable): VideoCodecStatus {
        if (errorReported.compareAndSet(false, true))
            runCatching { onError("çözücü hatası ($where, ${runCatching { inner.implementationName }.getOrNull()}): ${error.javaClass.name}: ${error.message}") }
        return VideoCodecStatus.ERROR
    }

    /** captureTimeNs → çözücüye geliş anı. AndroidVideoDecoder çıkan karenin zamanını girişteki captureTimeNs'ten verir. */
    private val arrivals = ConcurrentHashMap<Long, Long>()

    override fun initDecode(settings: VideoDecoder.Settings?, callback: VideoDecoder.Callback?): VideoCodecStatus = try {
        inner.initDecode(settings) { frame, decodeTimeMs, qp ->
            try {
                val now = clock()
                val arrived = arrivals.remove(frame.timestampNs) ?: now
                listener.onDecoded(frame.timestampNs / 1_000_000, arrived, now)
            } catch (error: Throwable) {
                failed("kare bildirimi", error)
            } finally {
                callback?.onDecodedFrame(frame, decodeTimeMs, qp)
            }
        }
    } catch (error: Throwable) {
        failed("başlatma", error)
    }

    override fun decode(frame: EncodedImage?, info: VideoDecoder.DecodeInfo?): VideoCodecStatus = try {
        // Çözücünün düşürdüğü karelerin kaydı birikmesin.
        if (arrivals.size > 240) arrivals.clear()
        if (frame != null) arrivals[frame.captureTimeNs] = clock()
        inner.decode(frame, info)
    } catch (error: Throwable) {
        failed("çözme", error)
    }

    override fun release(): VideoCodecStatus = try {
        arrivals.clear()
        inner.release()
    } catch (error: Throwable) {
        failed("kapatma", error)
    }

    override fun getImplementationName(): String = try {
        inner.implementationName ?: "?"
    } catch (error: Throwable) {
        "?"
    }
}

/** Gönderilmeyi bekleyen kare onayları (oynatıcının `_frameMetadataQueue`'su). */
internal class XboxFrameAckQueue(private val capacity: Int = 600) {
    private val items = ArrayDeque<XboxFrameAck>()

    @Synchronized fun offer(ack: XboxFrameAck) {
        // Kanal açılmadan biriken onaylar eskir; en yeniler tutulur.
        if (items.size >= capacity) items.removeFirst()
        items.addLast(ack)
    }

    @Synchronized fun drain(max: Int = XboxChannels.MAX_FRAME_ACKS): List<XboxFrameAck> {
        val count = minOf(max, items.size)
        return List(count) { items.removeFirst() }
    }

    @Synchronized fun clear() = items.clear()

    companion object {
        /** RTP damgasının milisaniyesinden serverDataKey (90 kHz saat; u32). */
        fun serverDataKey(rtpMs: Long): Long = (rtpMs * 90) and 0xFFFFFFFFL
    }
}
