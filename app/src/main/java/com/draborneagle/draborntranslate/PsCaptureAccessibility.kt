// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Locale

/** Opt-in PS App only. No guessed coordinate taps, account or bulk actions. */
class PsCaptureAccessibility:AccessibilityService(){
    private val main=Handler(Looper.getMainLooper())
    private val prefs by lazy{getSharedPreferences(AutoCaptureState.PREF,MODE_PRIVATE)}
    private val recent=linkedSetOf<String>()
    private var clickedDetail=false
    private var last=0L
    private var openedByUs=false
    private var lastRequest=""
    private val poll=object:Runnable{
        override fun run(){
            try{inspect()}catch(e:Exception){
                AutoCaptureState.accessStatus="Ekran okunamadı: "+e.javaClass.simpleName
                android.util.Log.w("DraBornCapture","Accessibility inspection failed",e)
            }finally{main.postDelayed(this,1800)}
        }
    }
    override fun onServiceConnected(){
        super.onServiceConnected()
        AutoCaptureState.accessConnected=true
        AutoCaptureState.accessStatus="Servis bağlı"
        main.removeCallbacks(poll);main.post(poll)
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?){}
    override fun onInterrupt(){clickedDetail=false;openedByUs=false;AutoCaptureState.accessStatus="Servis kesintiye uğradı"}
    override fun onUnbind(intent:Intent?):Boolean{
        disconnect();return super.onUnbind(intent)
    }
    override fun onDestroy(){disconnect();super.onDestroy()}
    private fun disconnect(){
        main.removeCallbacksAndMessages(null)
        AutoCaptureState.accessConnected=false
        AutoCaptureState.accessStatus="Servis bağlantısı yok"
    }
    private fun words(node:AccessibilityNodeInfo)=listOfNotNull(node.text?.toString(),node.contentDescription?.toString()).distinct().joinToString(" ").trim()
    private fun flat(root:AccessibilityNodeInfo):List<AccessibilityNodeInfo>{
        val out=mutableListOf<AccessibilityNodeInfo>();val q=java.util.ArrayDeque<AccessibilityNodeInfo>();q.add(root)
        while(q.isNotEmpty()&&out.size<500){val n=q.removeFirst();out.add(n);for(i in 0 until n.childCount)n.getChild(i)?.let(q::add)}
        return out
    }
    private fun click(n:AccessibilityNodeInfo):Boolean{
        var v:AccessibilityNodeInfo?=n
        repeat(4){val a=v?:return false;if(a.isClickable&&a.isEnabled&&a.isVisibleToUser)return a.performAction(AccessibilityNodeInfo.ACTION_CLICK);v=a.parent}
        return false
    }
    private fun isPs(n:AccessibilityNodeInfo?)=n?.packageName?.toString()=="com.scee.psxandroid"
    private fun inspect(){
        if(!AutoCaptureState.running||!prefs.getBoolean("autoDownload",false)){
            clickedDetail=false;openedByUs=false;AutoCaptureState.accessStatus="Bağlı · indirme yardımı bekliyor";return
        }
        if(SystemClock.elapsedRealtime()-last<5000)return
        // Our own floating overlay can become the active accessibility window.
        // Prefer a visible application window, never click through a different foreground app.
        val active=rootInActiveWindow
        val root=if(isPs(active))active else{
            val appWindows=windows.filter{it.type==AccessibilityWindowInfo.TYPE_APPLICATION}
            val foreground=appWindows.firstOrNull{it.isActive||it.isFocused}?:appWindows.maxByOrNull{it.layer}
            foreground?.root?.takeIf(::isPs)
        }
        if(root==null){AutoCaptureState.accessStatus="PS App ön planda bekleniyor";return}
        val nodes=flat(root).filter{it.isVisibleToUser}
        val labels=nodes.map{words(it).lowercase(Locale.ROOT)}
        if(labels.any{it.contains("sign in")||it.contains("password")||it.contains("şifre")||it.contains("oturum aç")}){
            AutoCaptureState.accessStatus="Hesap ekranı · işlem yapılmadı";return
        }
        val game=labels.any{it=="grand theft auto vi"||it=="gta vi"}
        val gallery=labels.any{it=="yakalamalar"||it=="captures"}
        if(gallery){
            clickedDetail=false;openedByUs=false
            if(!game){AutoCaptureState.accessStatus="GTA VI yakalamaları bekleniyor";return}
            val candidate=nodes.firstOrNull{n->
                val s=words(n).lowercase(Locale.ROOT)
                (s.contains("grand theft auto vi")||s.contains("gta vi")) &&
                    (s.contains("screenshot")||s.contains("video")||s.contains("ekran görüntüsü")||s.contains("capture")) &&
                    Regex("(\\d{4}-\\d{2}-\\d{2}|\\d{2}[./]\\d{2}[./]\\d{4}|[a-f0-9]{12,})").containsMatchIn(s) &&
                    s !in recent
            }
            if(candidate!=null&&click(candidate)){
                lastRequest=words(candidate).lowercase(Locale.ROOT)
                openedByUs=true;last=SystemClock.elapsedRealtime()
                AutoCaptureState.accessStatus="GTA VI yakalaması açıldı"
            }else AutoCaptureState.accessStatus="Galeri açık · benzersiz GTA VI yakalama etiketi yok; videoyu aç"
            return
        }
        if(!game){AutoCaptureState.accessStatus="GTA VI detay ekranı bekleniyor";return}
        if(clickedDetail){AutoCaptureState.accessStatus="İndirme istendi · dosya bekleniyor";return}
        val matching=nodes.filter{n->
            words(n).lowercase(Locale.ROOT) in setOf("indir","download","cihaza indir","download to device","kaydet","save to device","yakalamayı indir","download capture","videoyu indir","download video","ekran görüntüsünü indir")
        }
        val download=matching.singleOrNull()
        if(download==null){AutoCaptureState.accessStatus="GTA VI detayında etiketli indirme düğmesi yok; elle indir";return}
        if(click(download)){
            clickedDetail=true;last=SystemClock.elapsedRealtime()
            if(openedByUs&&lastRequest.isNotBlank()){recent.add(lastRequest);while(recent.size>100)recent.remove(recent.first())}
            AutoCaptureState.accessStatus="İndirme istendi · dosya bekleniyor"
        }else AutoCaptureState.accessStatus="İndirme düğmesine erişilemedi; elle indir"
    }
}
