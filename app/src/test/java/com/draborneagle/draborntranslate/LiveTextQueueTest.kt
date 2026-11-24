package com.draborneagle.draborntranslate

import org.junit.Assert.*
import org.junit.Test

class LiveTextQueueTest {
    @Test fun onlyPrivateNumericEndpoints() {
        assertEquals("ws://192.168.1.3:1234/api/ws/text/origin",LiveTextEndpoint.url("192.168.1.3","1234"))
        assertNotNull(LiveTextEndpoint.url("172.31.1.3","65535"))
        assertNotNull(LiveTextEndpoint.url("10.0.0.1","80"))
        listOf("8.8.8.8","127.0.0.1","172.32.0.1","192.168.1.256","192.168.01.1","192.168.1.1/path","localhost").forEach {
            assertNull(LiveTextEndpoint.url(it,"1234"))
        }
        assertNull(LiveTextEndpoint.url("192.168.1.3","65536"))
        assertNull(LiveTextEndpoint.url("192.168.1.3","0"))
    }
    @Test fun latestWinsAndDeduplicates() {
        val q=LiveTextQueue()
        assertTrue(q.submit("First line"))
        val old=q.next()!!
        assertFalse(q.submit(" First  line "))
        q.submit("Second"); q.submit("Third")
        val newest=q.next()!!
        assertEquals("Third",newest.text)
        assertFalse(q.isLatest(old))
        assertTrue(q.isLatest(newest))
        assertNull(q.next())
        q.clear()
        assertFalse(q.isLatest(newest))
        assertTrue(q.submit("Third"))
    }
    @Test fun blankClearsAndBounded() {
        val q=LiveTextQueue()
        q.submit("x".repeat(600))
        val cue=q.next()!!
        assertEquals(500,cue.text.length)
        q.submit(" ")
        assertFalse(q.isLatest(cue))
        assertNull(q.next())
    }
}
