package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudRegionTest {
    @Test fun normalizesEdges() {
        assertEquals(CloudRegion(95,0,5,100), CloudRegion(99,-10,80,200).normalized())
    }
    @Test fun movePreservesSize() {
        assertEquals(CloudRegion(60,70,40,30), CloudRegion(20,20,40,30).move(90,90))
    }
    @Test fun landscapeAndPortraitUseSameFractions() {
        val r = CloudRegion(10,20,40,30)
        assertEquals(Pixels(160,180,640,270), r.pixels(Pixels(0,0,1600,900)))
        assertEquals(Pixels(90,320,360,480), r.pixels(Pixels(0,0,900,1600)))
    }
}
