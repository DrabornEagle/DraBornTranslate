package com.draborneagle.draborntranslate
import org.junit.Assert.*
import org.junit.Test
class DialogueImportTest {
    @Test fun plainAndSearch(){
        val m=DialogueImport.parse("Hello there\nMeet me outside","Demo").single()
        assertEquals(2,m.cues.size)
        assertEquals(listOf(1),DialogueImport.search(m.cues,"meet outside"))
        assertEquals(4000L,DialogueImport.delay(m.cues,0,4000))
    }
    @Test fun timedAndRoundtrip(){
        val m=DialogueImport.parse("1\n00:00:01,000 --> 00:00:03,000\nHello\n\n2\n00:00:04,500 --> 00:00:06,000\nWorld","Demo")
        assertEquals(3500L,DialogueImport.delay(m.single().cues,0,4000))
        assertEquals(m,DialogueImport.parse(DialogueImport.encode(m),"Other"))
    }
    @Test fun bilingual(){
        val m=DialogueImport.parse("""{"missions":[{"name":"Demo","cues":[{"en":"Hi","tr":"Selam","speaker":"A"}]}]}""","").single()
        assertEquals("Selam",m.cues.single().tr)
        assertEquals(listOf(0),DialogueImport.search(m.cues,"selam"))
    }
}
