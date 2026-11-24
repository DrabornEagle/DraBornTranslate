// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import java.io.File

/** A manual dialogue companion. It never observes a Portal session. */
class SmartDialogueActivity : Activity() {
    private val engine by lazy { GameTranslatorEngine(this) }
    private val prefs by lazy { getSharedPreferences("smart_dialogue_v08",MODE_PRIVATE) }
    private val handler=Handler(Looper.getMainLooper())
    private val store by lazy { File(filesDir,"smart-dialogue.json") }
    private var missions=listOf<DialogueMission>()
    private var mission=0
    private var index=0
    private var generation=0
    private var loading=false
    private var ready=false
    private var closed=false
    private var playing=false
    private var speed=4000L
    private var showEnglish=true
    private val cache=object:LinkedHashMap<String,String>(128,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?):Boolean=size>200
    }
    private lateinit var missionTitle:TextView
    private lateinit var status:TextView
    private lateinit var original:TextView
    private lateinit var turkish:TextView
    private lateinit var position:TextView
    private lateinit var play:Button
    private lateinit var slider:SeekBar
    private lateinit var search:EditText
    private val tick=Runnable {
        if(playing && !closed) {
            if(index+1<cues().size) { index++; render() } else pause()
        }
    }
    private fun cues()=missions.getOrNull(mission)?.cues.orEmpty()

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speed=prefs.getLong("speed",4000).coerceIn(1000,15000)
        showEnglish=prefs.getBoolean("english",true)
        missions=runCatching { if(store.isFile) DialogueImport.parse(store.readText(),"Görev") else emptyList() }.getOrDefault(emptyList())
        mission=prefs.getInt("mission",0).coerceIn(0,(missions.size-1).coerceAtLeast(0))
        index=prefs.getInt("index",0).coerceIn(0,(cues().size-1).coerceAtLeast(0))
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xFF10172B.toInt())
            setPadding(dp(16),dp(18),dp(16),dp(18))
            setOnApplyWindowInsetsListener { v, ins ->
                if(android.os.Build.VERSION.SDK_INT>=30) {
                    val safe=ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    v.setPadding(dp(16)+safe.left,dp(18)+safe.top,dp(16)+safe.right,dp(18)+safe.bottom)
                };ins
            }
        }
        layout.addView(label("SMART DIALOGUE SYNC · v0.8",24,0xFF8CEBFF.toInt()))
        layout.addView(label("Görev rehberi · Kamera, mikrofon ve PC gerekmez. Oyunla otomatik senkron değildir.",14))
        fun row(vararg buttons:Button) {
            layout.addView(LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                buttons.forEach { addView(it,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(2),dp(3),dp(2),dp(3)) }) }
            })
        }
        row(button("Görev seç"){pickMission()},button("Dosya ekle"){pickFile()})
        row(button("Metin yapıştır"){paste()},button("GTA VI Explorer"){
            runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://six.mics.dev/dialogue"))) }
        })
        missionTitle=label("",20,0xFF8EF3BF.toInt());layout.addView(missionTitle)
        status=label("Çeviri motoru hazırlanıyor…",13,0xFF8CEBFF.toInt());layout.addView(status)
        search=EditText(this).apply {
            hint="İngilizce replikten 2–3 kelime";setSingleLine(true)
            setTextColor(Color.WHITE);setHintTextColor(0xFFBAC7D7.toInt())
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _,_,_-> find();true }
        }
        layout.addView(search)
        row(button("Replik bul"){find()},button("Ayarlar"){settings()})
        position=label("",14,0xFFBAC7D7.toInt());layout.addView(position)
        original=label("",16,0xFFBAC7D7.toInt()).apply { setTextIsSelectable(true) };layout.addView(original)
        turkish=label("",28).apply {
            minHeight=dp(125);setTextIsSelectable(true)
            background=GradientDrawable().apply { setColor(0xFF283758.toInt());cornerRadius=dp(16).toFloat() }
            setPadding(dp(14),dp(16),dp(14),dp(16))
        };layout.addView(turkish)
        slider=SeekBar(this).apply {
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,p:Int,user:Boolean){ if(user) position.text="Replik ${p+1} / ${cues().size}" }
                override fun onStartTrackingTouch(bar:SeekBar){ pause() }
                override fun onStopTrackingTouch(bar:SeekBar){ if(cues().isNotEmpty()){index=bar.progress;render()} }
            })
        };layout.addView(slider)
        row(button("‹ Önceki"){move(-1)},button("Sonraki ›"){move(1)})
        play=button("Otomatik ilerlet"){ if(playing) pause() else if(cues().isNotEmpty() && index<cues().lastIndex) { playing=true;play.text="Duraklat";schedule() } }
        layout.addView(play)
        layout.addView(button("Görevi başa al"){pause();index=0;render()})
        layout.addView(label("Otomatik ilerleme yalnızca dosyadaki zamanlara veya seçtiğin tahmini süreye dayanır. Oyundaki duraklama ve dallanmaları algılamaz.",13,0xFFBAC7D7.toInt()))
        layout.addView(button("Geri"){finish()})
        setContentView(ScrollView(this).apply { addView(layout) })
        render()
        engine.prepare { ok-> if(!closed){ready=ok;render()} }
    }
    private fun move(delta:Int){pause();if(cues().isNotEmpty()){index=(index+delta).coerceIn(0,cues().lastIndex);render()}}
    private fun pause(){playing=false;handler.removeCallbacks(tick);if(::play.isInitialized)play.text="Otomatik ilerlet"}
    private fun schedule(){
        handler.removeCallbacks(tick)
        if(playing && index<cues().lastIndex)handler.postDelayed(tick,DialogueImport.delay(cues(),index,speed)) else if(playing)pause()
    }
    private fun render(){
        if(closed)return
        val token=++generation
        val rows=cues()
        index=index.coerceIn(0,(rows.size-1).coerceAtLeast(0))
        missionTitle.text=missions.getOrNull(mission)?.name ?: "Görev ekle"
        position.text=if(rows.isEmpty())"Replik yok" else "Replik ${index+1} / ${rows.size}"
        slider.max=(rows.size-1).coerceAtLeast(0);slider.progress=index;slider.isEnabled=rows.isNotEmpty()
        prefs.edit().putInt("mission",mission).putInt("index",index).apply()
        val cue=rows.getOrNull(index)
        original.visibility=if(showEnglish)android.view.View.VISIBLE else android.view.View.GONE
        original.text=cue?.let{ if(it.speaker.isBlank())it.en else it.speaker+" · "+it.en }.orEmpty()
        if(cue==null){turkish.text="Önce bir görev dosyası ekle veya metin yapıştır.";status.text="Hazır dosya bekleniyor.";return}
        val key=cue.en
        val existing=cue.tr?:cache[key]
        if(existing!=null){turkish.text=existing;status.text="Türkçe · ${if(cue.tr!=null)"dosya" else "önbellek"}"}
        else if(!ready){turkish.text="Çeviri hazırlanıyor…";status.text="Türkçe motor hazırlanıyor / paket kontrolü."}
        else {
            turkish.text="Türkçeye çevriliyor…";status.text="Paket öncelikli çeviri"
            if(!loading) translateCurrent(token,cue)
        }
        schedule()
    }
    private fun translateCurrent(token:Int,cue:DialogueCue){
        loading=true
        engine.translate(TranslationInput(cue.en,readingArea=ReadingArea.SUBTITLES)){ reply->
            loading=false
            if(closed)return@translate
            reply.text?.takeIf{it.isNotBlank()}?.let{cache[cue.en]=it}
            if(token==generation){turkish.text=reply.text?:"Çeviri bulunamadı.";status.text=if(reply.text!=null)"Türkçe hazır" else "Çeviri başarısız · repliği tekrar seç."}
            else { val current=cues().getOrNull(index); if(current!=null && current.tr==null && cache[current.en]==null && ready)translateCurrent(generation,current) else if(current!=null){turkish.text=current.tr?:cache[current.en].orEmpty();status.text="Türkçe hazır"} }
        }
    }
    private fun pickMission(){
        pause()
        if(missions.isEmpty()){status.text="Önce görev dosyası ekle.";return}
        AlertDialog.Builder(this).setTitle("Görev seç").setItems(missions.map{it.name}.toTypedArray()){_,i->mission=i;index=0;render()}.show()
    }
    private fun find(){
        pause()
        val results=DialogueImport.search(cues(),search.text.toString()).take(100)
        if(results.isEmpty()){status.text="Bu görevde eşleşme yok.";return}
        AlertDialog.Builder(this).setTitle("Eşleşen replikler").setItems(results.map{ i->"${i+1}. "+cues()[i].en.take(130)}.toTypedArray()){_,j->index=results[j];render()}.show()
    }
    private fun settings(){
        pause()
        val items=arrayOf("2 saniye","4 saniye","6 saniye","8 saniye","İngilizceyi göster/gizle")
        AlertDialog.Builder(this).setTitle("Zamansız dosya · tahmini ilerleme").setItems(items){_,i->
            if(i==4){showEnglish=!showEnglish;prefs.edit().putBoolean("english",showEnglish).apply()}
            else {speed=listOf(2000L,4000L,6000L,8000L)[i];prefs.edit().putLong("speed",speed).apply()}
            render()
        }.show()
    }
    private fun paste(){
        pause()
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        val name=EditText(this).apply{hint="Görev adı";setSingleLine(true)}
        val text=EditText(this).apply{hint="Her satır bir İngilizce replik veya SRT / JSON";minLines=5}
        box.addView(name);box.addView(text)
        AlertDialog.Builder(this).setTitle("Diyalog ekle").setView(box).setPositiveButton("Kaydet"){_,_->importDialogue(text.text.toString(),name.text.toString())}.setNegativeButton("İptal",null).show()
    }
    @Suppress("DEPRECATION")
    private fun pickFile(){
        pause()
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"},820)
    }
    @Deprecated("Android Activity result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==820 && resultCode==RESULT_OK)data?.data?.let{uri->
            val name=runCatching{contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0).substringBeforeLast('.') else "Görev"}}.getOrNull()?:"Görev"
            status.text="Dosya okunuyor…"
            Thread{
                val result=runCatching{contentResolver.openInputStream(uri)?.bufferedReader()?.use{reader->
                    val buf=CharArray(8192);val out=StringBuilder()
                    while(true){val n=reader.read(buf);if(n<0)break;require(out.length+n<=2_000_000){"Dosya çok büyük."};out.append(buf,0,n)}
                    out.toString()
                }?:error("Dosya açılamadı.")}
                runOnUiThread{if(!closed)result.fold({importDialogue(it,name)},{status.text=it.message?:"Dosya okunamadı."})}
            }.start()
        }
    }
    private fun importDialogue(raw:String,name:String){
        pause()
        runCatching{
            val added=DialogueImport.parse(raw,name)
            val merged=missions.filterNot{old->added.any{it.name==old.name}}+added
            val encoded=DialogueImport.encode(merged)
            require(merged.size<=200 && encoded.length<=2_000_000){"Kütüphane sınırı aşıldı."}
            val atomic=android.util.AtomicFile(store);val stream=atomic.startWrite()
            try{stream.write(encoded.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
            missions=merged;mission=merged.indexOfFirst{it.name==added.first().name};index=0;render()
        }.onFailure{status.text=it.message?:"Diyalog eklenemedi."}
    }
    override fun onStop(){pause();super.onStop()}
    override fun onDestroy(){closed=true;handler.removeCallbacksAndMessages(null);engine.close();super.onDestroy()}
    private fun label(s:String,n:Int,c:Int=Color.WHITE)=TextView(this).apply{text=s;textSize=n.toFloat();setTextColor(c);setPadding(0,dp(7),0,dp(7))}
    private fun button(s:String,action:()->Unit)=Button(this).apply{text=s;isAllCaps=false;setTextColor(Color.WHITE);background=GradientDrawable().apply{setColor(0xFF364A76.toInt());cornerRadius=dp(12).toFloat()};setOnClickListener{action()}}
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
}
