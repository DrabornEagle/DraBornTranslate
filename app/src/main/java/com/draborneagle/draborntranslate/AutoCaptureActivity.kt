// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import org.json.JSONArray

class AutoCaptureActivity:Activity(){
    private val prefs by lazy{getSharedPreferences(AutoCaptureState.PREF,MODE_PRIVATE)}
    private val main=Handler(Looper.getMainLooper())
    private lateinit var status:TextView
    private lateinit var accessStatus:TextView
    private lateinit var history:TextView
    private lateinit var start:Button
    private var lastHistory=""
    private val refresh=object:Runnable{
        override fun run(){
            status.text=(if(AutoCaptureState.running)"● Aktif · " else "○ Durdu · ")+AutoCaptureState.status
            accessStatus.text=(if(AutoCaptureState.accessConnected)"● Erişilebilirlik bağlı · " else "○ Erişilebilirlik bağlı değil · ")+AutoCaptureState.accessStatus
            start.text=if(AutoCaptureState.running)"Takibi durdur" else "Otomatik çeviriyi başlat"
            val raw=prefs.getString(AutoCaptureState.HISTORY,"[]").orEmpty()
            if(raw!=lastHistory){
                lastHistory=raw
                history.text=runCatching{
                    val a=JSONArray(raw);val out=StringBuilder()
                    for(i in 0 until a.length()){
                        val item=a.getJSONObject(i);out.append("▰ ").append(item.optString("name")).append("\n")
                        val rows=item.getJSONArray("rows")
                        for(j in 0 until rows.length()){
                            val r=rows.getJSONObject(j);out.append(r.optString("en")).append("\n")
                            out.append(r.optString("tr").ifBlank{"Çeviri bulunamadı"}).append("\n\n")
                        }
                        if(rows.length()==0)out.append("Metin bulunamadı.\n\n")
                    };out.toString()
                }.getOrDefault("Geçmiş okunamadı.")
            }
            main.postDelayed(this,1000)
        }
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val layout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setBackgroundColor(0xFF10172B.toInt())
            setPadding(dp(16),dp(18),dp(16),dp(18))
            setOnApplyWindowInsetsListener{v,ins->
                if(Build.VERSION.SDK_INT>=30){
                    val safe=ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                    v.setPadding(dp(16)+safe.left,dp(18)+safe.top,dp(16)+safe.right,dp(18)+safe.bottom)
                };ins
            }
        }
        layout.addView(label("AUTO CAPTURE SYNC · v0.8.2",23,0xFF8CEBFF.toInt()))
        layout.addView(label("PS App → Pictures/PS App ve Movies/PS App. İndirilen fotoğraf ve videolar otomatik okunur. Canlı yayın değildir.",15))
        layout.addView(check("Brief · yalnızca sağdaki konuşma alanı","brief",true))
        layout.addView(check("Kayan Türkçe panel (üstte gösterme izni)","overlay",false))
        layout.addView(button("Kayan panel izni"){
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+packageName)))
        })
        layout.addView(check("Deneysel PS App indirme yardımı","autoDownload",false))
        layout.addView(label("İndirme yardımı yalnızca PS App açıkken, GTA VI ekranında erişilebilir ve açıkça etiketlenmiş yakalama/indirme düğmelerinde çalışır. Etiket yoksa elle indir. Hesap bilgilerine dokunmaz.",13,0xFFBAC7D7.toInt()))
        layout.addView(button("Erişilebilirlik ayarları"){
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        accessStatus=label("Erişilebilirlik bağlantısı kontrol ediliyor",14,0xFF8CEBFF.toInt());layout.addView(accessStatus)
        layout.addView(button("Erişilebilirlik bağlantısını yenile"){
            android.app.AlertDialog.Builder(this).setTitle("Bağlantıyı yenile").setMessage("Android erişilebilirlik sayfasında DraBornTranslate hizmetini kapat ve yeniden aç. Sonra buraya dön; gerçek bağlantı durumu gösterilecek.").setPositiveButton("Ayarları aç"){_,_->startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}.setNegativeButton("İptal",null).show()
        })
        start=button("Otomatik çeviriyi başlat"){if(AutoCaptureState.running)stopService(Intent(this,AutoCaptureService::class.java))else begin(false)}
        layout.addView(start)
        layout.addView(button("Mevcut dosyaları tara / yeniden çevir"){if(AutoCaptureState.running)Toast.makeText(this,"Önce takibi durdur.",Toast.LENGTH_SHORT).show()else begin(true)})
        layout.addView(button("PlayStation App'i aç"){
            val app=packageManager.getLaunchIntentForPackage("com.scee.psxandroid")
            if(app!=null)startActivity(app)else Toast.makeText(this,"PS App kurulu değil.",Toast.LENGTH_SHORT).show()
        })
        status=label("",14,0xFF8CEBFF.toInt());layout.addView(status)
        layout.addView(label("TÜRKÇE KONUŞMA GEÇMİŞİ",19,0xFF86F2C6.toInt()))
        history=label("Henüz çeviri yok.",18).apply{setTextIsSelectable(true)}
        layout.addView(history)
        layout.addView(button("Geçmişi temizle"){
            if(AutoCaptureState.running)Toast.makeText(this,"Önce takibi durdur.",Toast.LENGTH_SHORT).show()
            else android.app.AlertDialog.Builder(this).setMessage("Yerel çeviri geçmişi silinsin mi?").setPositiveButton("Sil"){_,_->prefs.edit().remove(AutoCaptureState.HISTORY).apply();history.text="Henüz çeviri yok."}.setNegativeButton("İptal",null).show()
        })
        layout.addView(button("Geri"){finish()})
        setContentView(ScrollView(this).apply{addView(layout)})
    }
    private fun permissions():Array<String>{
        return if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    private var old=false
    private fun begin(includeOld:Boolean){
        old=includeOld
        val need=permissions().filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(need.isNotEmpty()){requestPermissions(need.toTypedArray(),811);return}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),812);return
        }
        launchWatcher()
    }
    private fun launchWatcher(){
        if(permissions().any{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}){
            status.text="Fotoğraf ve video için tam erişim gerekli.";return
        }
        startForegroundService(Intent(this,AutoCaptureService::class.java).putExtra("old",old))
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==811){
            if(this.permissions().all{checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED})begin(old)
            else Toast.makeText(this,"Tüm fotoğraf ve videolara izin ver; seçili medya erişimi yeterli değil.",Toast.LENGTH_LONG).show()
        }else if(requestCode==812)launchWatcher()
    }
    override fun onResume(){super.onResume();main.post(refresh)}
    override fun onPause(){main.removeCallbacks(refresh);super.onPause()}
    private fun check(s:String,key:String,def:Boolean)=CheckBox(this).apply{
        text=s;setTextColor(Color.WHITE);isChecked=prefs.getBoolean(key,def)
        setOnCheckedChangeListener{_,value->prefs.edit().putBoolean(key,value).apply()}
    }
    private fun label(s:String,n:Int,c:Int=Color.WHITE)=TextView(this).apply{text=s;textSize=n.toFloat();setTextColor(c);setPadding(0,dp(8),0,dp(8))}
    private fun button(s:String,action:()->Unit)=Button(this).apply{
        text=s;isAllCaps=false;setTextColor(Color.WHITE)
        background=GradientDrawable().apply{setColor(0xFF364A76.toInt());cornerRadius=dp(12).toFloat()}
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)};setOnClickListener{action()}
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density+.5f).toInt()
}
