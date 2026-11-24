// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class DialogueCue(val en:String, val tr:String?=null, val at:Long?=null, val speaker:String="")
internal data class DialogueMission(val name:String,val cues:List<DialogueCue>)

internal object DialogueImport {
    private val time = Regex("""(\d{1,3}):(\d{2}):(\d{2})[,.](\d{3})""")
    private fun ms(s:String):Long? = time.find(s)?.groupValues?.let {
        val h=it[1].toLong(); val m=it[2].toLong(); val sec=it[3].toLong()
        if(m>59 || sec>59) null else ((h*60+m)*60+sec)*1000+it[4].toLong()
    }
    fun parse(raw:String, fallback:String):List<DialogueMission> {
        require(raw.length <= 2_000_000) { "Dosya çok büyük (en fazla 2 milyon karakter)." }
        val text=raw.removePrefix("\uFEFF").replace("\r\n","\n").replace('\r','\n').trim()
        require(text.isNotEmpty()) {"Dosya boş."}
        if(text.startsWith("{")) {
            val array=JSONObject(text).getJSONArray("missions")
            require(array.length() in 1..200) {"Görev sayısı 1–200 olmalı."}
            return (0 until array.length()).map { i ->
                val obj=array.getJSONObject(i)
                val rows=obj.getJSONArray("cues")
                require(rows.length() in 1..5000) {"Görev başına 1–5000 replik gerekir."}
                val cues=(0 until rows.length()).map { j ->
                    val row=rows.getJSONObject(j)
                    val en=row.getString("en").trim()
                    require(en.isNotBlank() && en.length<=2000) {"Geçersiz replik."}
                    DialogueCue(en,row.optString("tr").trim().takeIf { it.isNotBlank() }?.take(2000),
                        if(row.has("atMs") && !row.isNull("atMs")) row.getLong("atMs").also { require(it in 0..86_400_000L) } else null,
                        row.optString("speaker").take(100))
                }
                DialogueMission(obj.getString("name").trim().take(120).ifBlank { fallback },cues)
            }
        }
        val timed=text.contains("-->")
        val cues=if(timed) {
            text.split(Regex("\\n[ \\t]*\\n")).mapNotNull { block ->
                val lines=block.lines()
                val i=lines.indexOfFirst { it.contains("-->") }
                if(i<0) return@mapNotNull null
                val at=ms(lines[i].substringBefore("-->")) ?: return@mapNotNull null
                val en=lines.drop(i+1).joinToString(" ").trim().take(2000)
                if(en.isBlank()) null else DialogueCue(en,at=at)
            }.sortedBy { it.at }
        } else text.lines().map { it.trim() }.filter { it.isNotBlank() }.map { DialogueCue(it.take(2000)) }
        require(cues.isNotEmpty() && cues.size<=5000) {"1–5000 replik içeren geçerli bir dosya seç."}
        return listOf(DialogueMission(fallback.trim().take(120).ifBlank {"Görev"},cues))
    }
    fun encode(missions:List<DialogueMission>):String {
        val out=JSONArray()
        missions.forEach { m ->
            val cues=JSONArray()
            m.cues.forEach { c ->
                val row=JSONObject().put("en",c.en).put("speaker",c.speaker)
                c.tr?.let { row.put("tr",it) }; c.at?.let { row.put("atMs",it) }
                cues.put(row)
            }
            out.put(JSONObject().put("name",m.name).put("cues",cues))
        }
        return JSONObject().put("missions",out).toString()
    }
    fun search(cues:List<DialogueCue>, query:String):List<Int> {
        val q=query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if(q.isEmpty()) return emptyList()
        return cues.indices.filter { i ->
            val s=cues[i].let { (it.en+" "+it.tr.orEmpty()+" "+it.speaker).lowercase(Locale.ROOT) }
            q.all { s.contains(it) }
        }
    }
    fun delay(cues:List<DialogueCue>, i:Int, fallback:Long):Long {
        val a=cues.getOrNull(i)?.at
        val b=cues.getOrNull(i+1)?.at
        return if(a!=null && b!=null && b>a) (b-a).coerceIn(500,120_000) else fallback.coerceIn(1000,15000)
    }
}
