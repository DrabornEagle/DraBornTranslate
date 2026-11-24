// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** Conservative, opt-in assistance. Never click coordinates or interact with login,
 * purchase, account, share, or mixed-game bulk selection screens. */
class PsCaptureAccessibility:AccessibilityService(){
    private val main=Handler(Looper.getMainLooper())
    private val prefs by lazy{getSharedPreferences(AutoCaptureState.PREF,MODE_PRIVATE)}
    private val recent=linkedSetOf<String>()
    private var clickedDetail=false
    private var last=0L
    private val poll=object:Runnable{
        override fun run(){inspect();main.postDelayed(this,1800)}
    }
    override fun onServiceConnected(){super.onServiceConnected();main.removeCallbacks(poll);main.post(poll)}
    override fun onAccessibilityEvent(event:AccessibilityEvent?){}
    override fun onInterrupt(){clickedDetail=false}
    override fun onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun words(node:AccessibilityNodeInfo)=listOfNotNull(node.text?.toString(),node.contentDescription?.toString()).joinToString(" ").trim()
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
    private fun inspect(){
        if(!AutoCaptureState.running||!prefs.getBoolean("autoDownload",false)){clickedDetail=false;return}
        if(SystemClock.elapsedRealtime()-last<5000)return
        val root=rootInActiveWindow?:return
        if(root.packageName?.toString()!="com.scee.psxandroid")return
        val nodes=flat(root).filter{it.isVisibleToUser}
        val labels=nodes.map{words(it).lowercase(Locale.ROOT)}
        if(labels.any{it.contains("sign in")||it.contains("password")||it.contains("şifre")||it.contains("oturum aç")})return
        val game=labels.any{it=="grand theft auto vi"||it=="gta vi"}
        if(!game)return
        val gallery=labels.any{it=="yakalamalar"||it=="captures"}
        if(gallery){
            clickedDetail=false
            // Only a capture whose own semantic label identifies GTA and a unique timestamp/id.
            // Do not guess based on screen position, duration, or game group.
            val candidate=nodes.firstOrNull{n->
                val s=words(n).lowercase(Locale.ROOT)
                (s.contains("grand theft auto vi")||s.contains("gta vi")) &&
                    (s.contains("screenshot")||s.contains("video")||s.contains("ekran görüntüsü")||s.contains("capture")) &&
                    Regex("(\\d{4}-\\d{2}-\\d{2}|\\d{2}[./]\\d{2}[./]\\d{4}|[a-f0-9]{12,})").containsMatchIn(s) &&
                    s !in recent
            }
            if(candidate!=null&&click(candidate)){
                recent.add(words(candidate).lowercase(Locale.ROOT));while(recent.size>100)recent.remove(recent.first())
                last=SystemClock.elapsedRealtime()
            }
            return
        }
        if(clickedDetail)return
        val download=nodes.singleOrNull{n->
            val s=words(n).lowercase(Locale.ROOT)
            s in setOf("indir","download","cihaza indir","download to device","kaydet","save to device")
        }?:return
        if(click(download)){clickedDetail=true;last=SystemClock.elapsedRealtime()}
    }
}
