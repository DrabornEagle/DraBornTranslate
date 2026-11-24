// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** User-initiated live text receiver for LunaTranslator's documented origin stream.
 * No PSN credentials, camera, microphone, or Portal session interception. */
class LiveTextActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("live_text_v072", MODE_PRIVATE) }
    private val engine by lazy { GameTranslatorEngine(this) }
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val queue = LiveTextQueue()
    private var socket: WebSocket? = null
    private var generation = 0
    private var busy = false
    private var ready = false
    private var destroyed = false
    private var connected = false
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var original: TextView
    private lateinit var translated: TextView
    private lateinit var history: TextView
    private val recent = java.util.ArrayDeque<String>()
    private val expire = Runnable {
        queue.clear()
        original.text = ""
        translated.text = ""
        if (connected) status.text = "Bağlı · yeni altyazı bekleniyor"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF10172B.toInt())
            setPadding(dp(18), dp(20), dp(18), dp(24))
            setOnApplyWindowInsetsListener { v, ins ->
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val safe = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    v.setPadding(dp(18)+safe.left, dp(20)+safe.top, dp(18)+safe.right, dp(24)+safe.bottom)
                }
                ins
            }
        }
        layout.addView(label("LIVE STREAM · 0.7.2", 25, 0xFF8CEBFF.toInt()))
        layout.addView(label("LunaTranslator · canlı İngilizce metin → GTA 6 paketi → Türkçe", 17))
        layout.addView(label("Bilgisayarda oyunun görüntüsünü aç, LunaTranslator OCR alanını seç ve ağ hizmetini etkinleştir. Aynı güvenilir Wi-Fi ağına bağlan. Bu mod PS Portal bulut oturumunu tek başına aynalamaz.", 14))
        host = input("Bilgisayarın yerel IPv4 adresi (192.168.x.x)", prefs.getString("host","").orEmpty())
        port = input("Luna ağ hizmeti portu", prefs.getString("port","").orEmpty()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(host)
        layout.addView(port)
        button = Button(this).apply {
            text = "Canlı bağlantıyı başlat"; isEnabled = false; isAllCaps = false
            setOnClickListener { if (socket != null) disconnect("Bağlantı durduruldu.") else connect() }
        }
        layout.addView(button)
        status = label("Çeviri motoru hazırlanıyor…", 14, 0xFF8CEBFF.toInt())
        layout.addView(status)
        layout.addView(label("TÜRKÇE ALTYAZI", 13, 0xFF86F2C6.toInt()))
        translated = label("", 28).apply {
            minHeight = dp(112); setTextIsSelectable(true)
            background = GradientDrawable().apply { setColor(0xFF263454.toInt()); cornerRadius=dp(16).toFloat() }
            setPadding(dp(14),dp(14),dp(14),dp(14))
        }
        layout.addView(translated)
        original = label("", 16, 0xFFBAC7D7.toInt()).apply { setTextIsSelectable(true) }
        layout.addView(original)
        layout.addView(label("SON ÇEVİRİLER", 13, 0xFF8CEBFF.toInt()))
        history = label("", 16).apply { setTextIsSelectable(true) }
        layout.addView(history)
        layout.addView(label("Bağlantı şifrelenmez: yalnızca kendi güvenilir yerel ağında kullan. Luna ağ portunu internete açma. Ekran görüntüsü ya da PSN bilgisi aktarılmaz.", 12, 0xFFBAC7D7.toInt()))
        layout.addView(Button(this).apply { text="Geri"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(layout) })
        engine.prepare { ok ->
            if (!destroyed) {
                ready=ok; button.isEnabled=ok
                status.text=if(ok) "Hazır · yerel ağ adresini gir." else "Çeviri motoru hazırlanamadı."
            }
        }
    }

    private fun connect() {
        if (!ready || destroyed) return
        val url=LiveTextEndpoint.url(host.text.toString(),port.text.toString())
        if(url==null) { status.text="Geçerli yerel IPv4 ve port gir (10.x, 172.16–31.x, 192.168.x)."; return }
        prefs.edit().putString("host",host.text.toString().trim()).putString("port",port.text.toString().trim()).apply()
        val token=++generation
        queue.clear()
        status.text="LunaTranslator'a bağlanılıyor…"
        button.text="Bağlantıyı durdur"
        socket=client.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response) {
                main.post { if(token==generation && !destroyed) { connected=true; status.text="Canlı · yeni altyazı bekleniyor" } }
            }
            override fun onMessage(webSocket:WebSocket,text:String) {
                if(text.length>8192) return
                main.post { if(token==generation && connected && !destroyed) receive(text) }
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {
                webSocket.close(1000,null)
                main.post { if(token==generation && !destroyed) disconnect("Kaynak bağlantıyı kapattı.") }
            }
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) {
                main.post { if(token==generation && !destroyed) disconnect("Bağlantı kapandı.") }
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {
                response?.close()
                main.post { if(token==generation && !destroyed) disconnect("Bağlanılamadı · IP, port, Luna ağ hizmeti ve güvenlik duvarını kontrol et.") }
            }
        })
    }

    private fun receive(raw:String) {
        main.removeCallbacks(expire)
        main.postDelayed(expire,6000)
        if (!queue.submit(raw)) return
        original.text=raw.trim().take(500)
        translated.text=""
        if(raw.isBlank()) { status.text="Canlı · yeni altyazı bekleniyor"; return }
        status.text="Canlı · Türkçeye çevriliyor…"
        pump()
    }

    private fun pump() {
        if(busy || destroyed || !connected) return
        val cue=queue.next() ?: return
        busy=true
        engine.translate(TranslationInput(cue.text,readingArea=ReadingArea.SUBTITLES)) { reply ->
            busy=false
            if(!destroyed && connected && queue.isLatest(cue)) {
                val text=reply.text?.takeIf { it.isNotBlank() }
                translated.text=text ?: "Çeviri bulunamadı."
                status.text=if(text!=null) "Canlı · Türkçe" else "Canlı · çeviri bulunamadı"
                if(text!=null) {
                    recent.addFirst(text)
                    while(recent.size>8) recent.removeLast()
                    history.text=recent.joinToString("\n\n")
                }
            }
            pump()
        }
    }

    private fun disconnect(message:String) {
        generation++; connected=false; queue.clear()
        main.removeCallbacks(expire)
        socket?.cancel(); socket=null
        button.text="Canlı bağlantıyı başlat"
        original.text=""; translated.text=""; status.text=message
    }

    override fun onStop() {
        if(socket!=null) disconnect("Arka plana geçildi · yeniden bağlan.")
        super.onStop()
    }

    override fun onDestroy() {
        destroyed=true; generation++; queue.clear()
        main.removeCallbacksAndMessages(null)
        socket?.cancel()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        engine.close()
        super.onDestroy()
    }

    private fun label(s:String,size:Int,color:Int=Color.WHITE)=TextView(this).apply {
        text=s; textSize=size.toFloat(); setTextColor(color); setPadding(0,dp(8),0,dp(8))
    }
    private fun input(h:String,value:String)=EditText(this).apply {
        hint=h; setText(value); setTextColor(Color.WHITE); setHintTextColor(0xFFBAC7D7.toInt())
        isSingleLine=true
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
}
