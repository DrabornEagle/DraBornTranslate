// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.util.Locale

internal object AutoCaptureRules {
    fun allowed(path:String,name:String,video:Boolean):Boolean {
        val folder=if(video)"Movies/PS App/" else "Pictures/PS App/"
        return path.trimEnd('/')+"/"==folder && if(video) name.endsWith(".mp4",true)
            else listOf(".jpg",".jpeg",".png",".webp").any { name.endsWith(it,true) }
    }
    fun normalize(s:String)=s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    private val reviewedLines=mapOf(
        "kirk i am in active pursuit of skunk ape and have to limit my rf radiation" to "Kirk: Şu anda Skunk Ape'in peşindeyim; bu yüzden radyo frekansı yayılımımı sınırlamam gerekiyor.",
        "wanna get murdered pick up a hitchhiker today" to "Öldürülmek mi istiyorsun? Bugün bir otostopçu al!",
        "pick up a hitchhiker today" to "Bugün bir otostopçu al!",
        "lucia hey what you doing wanna have a little fun" to "Lucia: Hey, ne yapıyorsun? Biraz eğlenmek ister misin?"
    )
    fun reviewed(text:String):String?=reviewedLines[normalize(text)]
    fun addLine(lines:MutableList<String>,raw:String):Boolean {
        val s=raw.trim().replace(Regex("\\s+")," ").take(500)
        val key=normalize(s)
        if(s.count{it.isLetter()}<3 || key in setOf("all","dialogue","help text","objective text","brief","map","progress","exit","jason lucia"))return false
        if(lines.any{normalize(it)==key})return false
        if(lines.size>=300)return false
        lines.add(s);return true
    }
}
