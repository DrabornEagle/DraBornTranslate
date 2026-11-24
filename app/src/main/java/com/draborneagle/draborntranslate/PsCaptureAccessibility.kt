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
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Opt-in PS App only. No guessed coordinate taps, account or bulk actions. */
class PsCaptureAccessibility:AccessibilityService(){
    private val main=Handler(Looper.getMainLooper())
    private val prefs by lazy{getSharedPreferences(AutoCaptureState.PREF,MODE_PRIVATE)}
    private val recent=linkedSetOf<String>()
    private var clickedDetail=false
    private var last=0L
    private var openedByUs=false
    private var lastRequest=""
    private val visualReader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var visualBusy=false
    private var visualOpened=false
    private var visualDownloadAt=0L
    private var visualBaseline:IntArray?=null
    private var visualPending:IntArray?=null
    private var visualBeforeFiles=0L
    private var refreshAt=0L
    private var visualOpenedAt=0L
    private var visualLastAction=0L
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
    override fun onDestroy(){disconnect();runCatching{visualReader.close()};super.onDestroy()}
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
            clickedDetail=false;openedByUs=false;visualOpened=false;visualDownloadAt=0L;visualPending=null;AutoCaptureState.accessStatus="Bağlı · indirme yardımı bekliyor";return
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
        if(prefs.getBoolean("visualDownload",false) && android.os.Build.VERSION.SDK_INT>=30){
            visualInspect();return
        }
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

    @android.annotation.TargetApi(30)
    private fun visualInspect(){
        val now=SystemClock.elapsedRealtime()
        if(visualBusy||now-visualLastAction<2300)return
        visualBusy=true
        takeScreenshot(Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback{
            override fun onFailure(errorCode:Int){
                visualBusy=false
                AutoCaptureState.accessStatus="Görsel yakalama kullanılamıyor ($errorCode) · elle indir"
            }
            override fun onSuccess(screenshot:ScreenshotResult){
                val buffer=screenshot.hardwareBuffer
                val frame=try{
                    val hw=Bitmap.wrapHardwareBuffer(buffer,screenshot.colorSpace)
                    try{hw?.copy(Bitmap.Config.ARGB_8888,false)}finally{hw?.recycle()}
                }catch(e:Exception){null}finally{buffer.close()}
                if(frame==null){visualBusy=false;return}
                if(frame.width>=frame.height){frame.recycle();visualBusy=false;AutoCaptureState.accessStatus="Görsel mod için telefonu dik tut";return}
                visualReader.process(InputImage.fromBitmap(frame,0))
                    .addOnSuccessListener{ result->
                        try{
                            if(!AutoCaptureState.running||!prefs.getBoolean("autoDownload",false)||!prefs.getBoolean("visualDownload",false))return@addOnSuccessListener
                            if(!isPs(rootInActiveWindow) && windows.none{it.type==AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused||it.isActive) && isPs(it.root)})return@addOnSuccessListener
                            val lines=result.textBlocks.flatMap{it.lines}
                            val labels=lines.map{it.text.lowercase(Locale.ROOT)}
                            if(labels.any{it.contains("password")||it.contains("sign in")||it.contains("şifre")||it.contains("oturum aç")||it.contains("satın al")})return@addOnSuccessListener
                            val title=lines.firstOrNull{it.text.trim().equals("Grand Theft Auto VI",true)}?.boundingBox
                            val gallery=lines.any{val b=it.boundingBox; b!=null&&b.centerY()<frame.height*.18&&(it.text.trim().equals("Yakalamalar",true)||it.text.trim().equals("Captures",true))}
                            val t=SystemClock.elapsedRealtime()
                            if(gallery){
                                if(visualOpened){
                                    // User backed out or the viewer closed; do not blindly retry immediately.
                                    visualOpened=false;visualDownloadAt=0L;visualPending=null
                                }
                                if(title==null || title.centerY()>frame.height*.24 || title.centerY()<frame.height*.10){
                                    AutoCaptureState.accessStatus="GTA VI başlığı galerinin üstünde bekleniyor";return@addOnSuccessListener
                                }
                                if(t-refreshAt>=12000){
                                    refreshAt=t;visualLastAction=t
                                    AutoCaptureState.accessStatus="PS App galerisi yenileniyor"
                                    gesture(frame.width*.51f,frame.height*.30f,frame.width*.51f,frame.height*.65f,420)
                                    return@addOnSuccessListener
                                }
                                // First full-size card below GTA header; bounded to the supplied portrait layout.
                                val y=(title.bottom+frame.height*.035).toInt()
                                val fingerprint=signature(frame,y)
                                if(fingerprint==null)return@addOnSuccessListener
                                if(visualBaseline!=null && !changed(visualBaseline!!,fingerprint)){
                                    AutoCaptureState.accessStatus="Galeri takipte · yeni ilk GTA VI yakalaması bekleniyor";return@addOnSuccessListener
                                }
                                if(visualPending!=null && changed(visualPending!!,fingerprint)){visualPending=fingerprint;return@addOnSuccessListener}
                                if(visualPending==null){visualPending=fingerprint;return@addOnSuccessListener}
                                val tapY=y+frame.width*.20f
                                if(tapY>frame.height*.44)return@addOnSuccessListener
                                if(gesture(frame.width*.50f,tapY,frame.width*.50f,tapY,75)){
                                    visualOpened=true;visualOpenedAt=t;visualBeforeFiles=AutoCaptureState.downloadSerial
                                    visualLastAction=t;visualDownloadAt=0L
                                    AutoCaptureState.accessStatus="Yeni ilk GTA VI yakalaması açılıyor"
                                }
                            }else if(visualOpened){
                                // Only the viewer we opened, with GTA VI title in its known top bar.
                                if(title==null||title.centerY()>frame.height*.14||title.centerY()<frame.height*.035){
                                    if(t-visualOpenedAt>12000){visualOpened=false;visualPending=null;AutoCaptureState.accessStatus="Görüntüleyici doğrulanamadı · elle indir"}
                                    return@addOnSuccessListener
                                }
                                if(visualDownloadAt==0L){
                                    val root=rootInActiveWindow
                                    val target=if(isPs(root))flat(root!!).filter{it.isVisibleToUser}.firstOrNull{words(it).lowercase(Locale.ROOT) in setOf("indir","download","cihaza indir","download capture","videoyu indir","ekran görüntüsünü indir","download video")}else null
                                    val ok=target?.let(::click)==true || gesture(frame.width*.894f,frame.height*.947f,frame.width*.894f,frame.height*.947f,75)
                                    if(ok){visualDownloadAt=t;visualLastAction=t;AutoCaptureState.accessStatus="İndirme istendi · yeni dosya bekleniyor"}
                                }else if(AutoCaptureState.downloadSerial>visualBeforeFiles){
                                    visualBaseline=visualPending;visualPending=null;visualOpened=false;visualDownloadAt=0L;visualLastAction=t
                                    gesture(frame.width*.946f,frame.height*.074f,frame.width*.946f,frame.height*.074f,75)
                                    AutoCaptureState.accessStatus="Dosya algılandı · galeriye dönülüyor"
                                }else if(t-visualDownloadAt>60000){
                                    visualOpened=false;visualBaseline=visualPending;visualPending=null
                                    AutoCaptureState.accessStatus="İndirme doğrulanamadı · kontrol edip elle indir"
                                }
                            }else AutoCaptureState.accessStatus="GTA VI Yakalamalar listesine dön"
                        }catch(e:Exception){AutoCaptureState.accessStatus="Görsel mod hatası: "+e.javaClass.simpleName}
                    }
                    .addOnFailureListener{AutoCaptureState.accessStatus="Galeri görsel OCR başarısız"}
                    .addOnCompleteListener{frame.recycle();visualBusy=false}
            }
        })
    }
    private fun gesture(x:Float,y:Float,x2:Float,y2:Float,duration:Long):Boolean{
        val path=Path().apply{moveTo(x,y);lineTo(x2,y2)}
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build(),null,null)
    }
    private fun signature(b:Bitmap,y:Int):IntArray?{
        val h=(b.width*.42).toInt()
        if(y<0||y+h>=b.height)return null
        return IntArray(64){i->
            val x=(b.width*(.14+.72*((i%8+.5)/8))).toInt()
            val yy=y+(h*((i/8+.5)/8)).toInt()
            b.getPixel(x,yy)
        }
    }
    private fun changed(a:IntArray,b:IntArray):Boolean{
        var diff=0
        for(i in a.indices){
            val p=a[i];val q=b[i]
            diff+=kotlin.math.abs(android.graphics.Color.red(p)-android.graphics.Color.red(q))+
                kotlin.math.abs(android.graphics.Color.green(p)-android.graphics.Color.green(q))+
                kotlin.math.abs(android.graphics.Color.blue(p)-android.graphics.Color.blue(q))
        }
        return diff>64*3*18
    }
}
