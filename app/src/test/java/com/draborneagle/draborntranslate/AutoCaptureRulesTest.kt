package com.draborneagle.draborntranslate
import org.junit.Assert.*
import org.junit.Test
class AutoCaptureRulesTest {
    @Test fun folders(){
        assertTrue(AutoCaptureRules.allowed("Pictures/PS App/","1a20-screenshotUrl.jpg",false))
        assertTrue(AutoCaptureRules.allowed("Movies/PS App/","1a20-master_playlist.mp4",true))
        assertFalse(AutoCaptureRules.allowed("DCIM/Camera/","private.jpg",false))
        assertFalse(AutoCaptureRules.allowed("Movies/PS App/","x.jpg",true))
    }
    @Test fun dedupe(){
        val lines=mutableListOf<String>()
        assertTrue(AutoCaptureRules.addLine(lines,"Jason: Hello there."))
        assertFalse(AutoCaptureRules.addLine(lines,"Jason:  Hello there!"))
        assertFalse(AutoCaptureRules.addLine(lines,"Dialogue"))
        assertTrue(AutoCaptureRules.addLine(lines,"Lucia: Hello there."))
        assertEquals(2,lines.size)
    }
}
