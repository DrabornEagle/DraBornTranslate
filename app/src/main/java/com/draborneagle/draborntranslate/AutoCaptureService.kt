// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.*
import android.content.*
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

internal object AutoCaptureState {
    const val PREF="auto_capture_v081"
    const val HISTORY="capture_history"
    @Volatile var running=false
    @Volatile var status="Hazır"
    @Volatile var accessConnected=false
    @Volatile var accessStatus="Servis bağlantısı yok"
}
class AutoCaptureService:Service() {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val engine by lazy { GameTranslatorEngine(this) }
    private val prefs by lazy { getSharedPreferences(AutoCaptureState.PREF,MODE_PRIVATE) }
    @Volatile private var stopped=false
    private var ready=false
    private var busy=false
    private var overlay:TextView?=null
    private val seen=linkedSetOf<String>()
    private val failures=mutableMapOf<String,Int>()
    private val stable=mutableMapOf<String,Long>()
    private var since=0L
    private var includeOld=false
    private val poll=object:Runnable{
        override fun run(){
            if(stopped)return
            if(ready && !busy){busy=true;worker.execute{
                try { scan() } catch(e:Exception){ publish("Klasör taranamadı: "+(e.message?:"izin kontrolü")) }
                finally{main.post{busy=false}}
            }}
            main.postDelayed(this,4000)
        }
    }
    private val observer=object:ContentObserver(main){
        override fun onChange(selfChange:Boolean){if(!busy){main.removeCallbacks(poll);main.post(poll)}}
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
        if(AutoCaptureState.running)return START_NOT_STICKY
        AutoCaptureState.running=true
        includeOld=intent?.getBooleanExtra("old",false)==true
        since=System.currentTimeMillis()/1000-5
        seen.addAll(prefs.getStringSet("seen",emptySet()).orEmpty())
        if(includeOld){seen.clear();prefs.edit().remove("seen").apply()}
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("auto_capture","PS App otomatik çeviri",NotificationManager.IMPORTANCE_LOW))
        startForeground(814,notification("Türkçe motor hazırlanıyor"))
        if(prefs.getBoolean("overlay",false)&&Settings.canDrawOverlays(this))showOverlay()
        publish("Türkçe motor hazırlanıyor")
        engine.prepare{ok->
            if(!stopped){ready=ok;if(ok){publish("PS App dosyaları bekleniyor");main.post(poll)}else{publish("Çeviri motoru hazırlanamadı");stopSelf()}}
        }
        runCatching{
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer)
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,true,observer)
        }
        return START_NOT_STICKY
    }
    private data class Media(val uri:Uri,val key:String,val name:String,val size:Long,val date:Long,val video:Boolean)
    private fun list(video:Boolean):List<Media>{
        val base=if(video)MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val path=if(video)"Movies/PS App/" else "Pictures/PS App/"
        val cols=arrayOf("_id","_display_name","relative_path","_size","date_added","date_modified")
        val out=mutableListOf<Media>()
        contentResolver.query(base,cols,"relative_path = ? AND is_pending = 0",arrayOf(path),"date_added DESC")?.use{c->
            while(c.moveToNext()&&out.size<200){
                val id=c.getLong(0);val name=c.getString(1).orEmpty();val folder=c.getString(2).orEmpty()
                val size=c.getLong(3);val date=c.getLong(4);val modified=c.getLong(5)
                if(size>0 && AutoCaptureRules.allowed(folder,name,video) && (includeOld||maxOf(date,modified)>=since)){
                    out.add(Media(ContentUris.withAppendedId(base,id),"$video:$id:$size:$modified",name,size,date,video))
                }
            }
        }
        return out
    }
    private fun scan(){
        val all=(list(false)+list(true)).sortedBy{it.date}
        if(all.isEmpty() && includeOld)publish("PS App klasörlerinde MediaStore dosyası bulunamadı · medya izinlerini kontrol et")
        for(m in all){
            if(stopped)return
            if(m.key in seen || (failures[m.key]?:0)>=3)continue
            if(stable.put(m.key,m.size)!=m.size)continue // two scans, unchanged size and finalized by MediaStore
            publish("Okunuyor · "+m.name)
            val lines=mutableListOf<String>()
            try{
                if(m.video)readVideo(m.uri,lines)else{
                    val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,m.uri)){d,info,_->
                        d.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                        val ratio=minOf(1.0,1920.0/maxOf(info.size.width,info.size.height))
                        d.setTargetSize((info.size.width*ratio).toInt().coerceAtLeast(1),(info.size.height*ratio).toInt().coerceAtLeast(1))
                    }
                    readBitmap(bitmap,lines)
                }
                if(stopped)return
                val rows=JSONArray()
                for((i,line) in lines.withIndex()){
                    if(stopped)return
                    publish("Türkçeye çevriliyor · "+(i+1)+"/"+lines.size)
                    val tr=translate(line)
                    rows.put(JSONObject().put("en",line).put("tr",tr.orEmpty()))
                }
                if(stopped)return
                val entries=runCatching{JSONArray(prefs.getString(AutoCaptureState.HISTORY,"[]"))}.getOrDefault(JSONArray())
                val result=JSONArray().put(JSONObject().put("name",m.name).put("date",System.currentTimeMillis()).put("rows",rows))
                for(i in 0 until minOf(entries.length(),29))result.put(entries.getJSONObject(i))
                seen.add(m.key);while(seen.size>1000)seen.remove(seen.first())
                prefs.edit().putString(AutoCaptureState.HISTORY,result.toString()).putStringSet("seen",seen.toSet()).apply()
                val latest=(0 until rows.length()).map{rows.getJSONObject(it).optString("tr")}.filter{it.isNotBlank()}.takeLast(2).joinToString("\n")
                publish(if(lines.isEmpty())"Metin bulunamadı · Brief ayarını kapatıp yeniden tara" else if(latest.isBlank())"Metin okundu · çeviri bulunamadı" else latest)
            }catch(e:Exception){
                if(stopped)return
                failures[m.key]=(failures[m.key]?:0)+1
                publish("Dosya okunamadı · "+m.name)
            }
        }
    }
    private fun readBitmap(bitmap:Bitmap,lines:MutableList<String>){
        var crop:Bitmap?=null
        try{
            if(stopped)return
            // Brief dialogue is on the right; ordinary screenshot mode uses the whole image.
            val input=if(prefs.getBoolean("brief",true)){
                val x=(bitmap.width*.44).toInt().coerceIn(0,bitmap.width-1)
                val y=(bitmap.height*.10).toInt().coerceIn(0,bitmap.height-1)
                Bitmap.createBitmap(bitmap,x,y,bitmap.width-x,(bitmap.height*.78).toInt().coerceIn(1,bitmap.height-y)).also{crop=it}
            }else bitmap
            val result=Tasks.await(recognizer.process(InputImage.fromBitmap(input,0)),20,TimeUnit.SECONDS)
            result.textBlocks.forEach{block->
                val current=StringBuilder()
                block.lines.forEach{line->
                    val s=line.text.trim()
                    if(s.contains(':')&&current.isNotEmpty()){
                        AutoCaptureRules.addLine(lines,current.toString());current.clear()
                    }
                    if(current.isNotEmpty())current.append(' ')
                    current.append(s)
                }
                if(current.isNotEmpty())AutoCaptureRules.addLine(lines,current.toString())
            }
        }finally{if(crop!=null&&crop!==bitmap)crop?.recycle();bitmap.recycle()}
    }
    private fun readVideo(uri:Uri,lines:MutableList<String>){
        val retriever=MediaMetadataRetriever()
        try{
            retriever.setDataSource(this,uri)
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.coerceAtMost(180_000)?:error("Video süresi okunamadı")
            val step=if(prefs.getBoolean("brief",true))1000L else 750L
            var t=0L
            while(t<duration&&!stopped&&lines.size<300){
                publish("Video OCR · "+t/1000+" / "+duration/1000+" sn")
                val frame=retriever.getFrameAtTime(t*1000,MediaMetadataRetriever.OPTION_CLOSEST)
                if(frame!=null)readBitmap(frame,lines)
                t+=step
            }
        }finally{retriever.release()}
    }
    private fun translate(line:String):String?{
        val latch=CountDownLatch(1);var result:String?=null
        engine.translate(TranslationInput(line,readingArea=ReadingArea.GAME_TEXT)){reply->result=reply.text;latch.countDown()}
        if(!latch.await(45,TimeUnit.SECONDS))return null
        return result
    }
    private fun notification(message:String):Notification{
        val open=PendingIntent.getActivity(this,814,Intent(this,AutoCaptureActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,815,Intent(this,AutoCaptureService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"auto_capture").setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("DraBornTranslate · Auto Capture").setContentText(message.take(120))
            .setStyle(Notification.BigTextStyle().bigText(message.take(500))).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Durdur",stop).build()).build()
    }
    private fun publish(message:String){
        main.post{
            if(stopped)return@post
            AutoCaptureState.status=message
            getSystemService(NotificationManager::class.java).notify(814,notification(message))
            overlay?.text=message.take(500)
        }
    }
    private fun showOverlay(){
        val view=TextView(this).apply{
            text="PS App yakalamaları bekleniyor";textSize=17f;setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xDD15233E.toInt());setPadding(20,12,20,12);gravity=Gravity.CENTER;maxLines=4
        }
        val wm=getSystemService(WindowManager::class.java)
        val lp=WindowManager.LayoutParams(-1,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,android.graphics.PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.BOTTOM;lp.y=150;lp.alpha=0.78f
        runCatching{wm.addView(view,lp);overlay=view}
    }
    override fun onTimeout(startId:Int,fgsType:Int){stopSelf()}
    override fun onDestroy(){
        stopped=true;AutoCaptureState.running=false;AutoCaptureState.status="Durduruldu"
        main.removeCallbacksAndMessages(null);runCatching{contentResolver.unregisterContentObserver(observer)}
        overlay?.let{runCatching{getSystemService(WindowManager::class.java).removeView(it)}};overlay=null
        worker.shutdownNow();runCatching{recognizer.close()};engine.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
