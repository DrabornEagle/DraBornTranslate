// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortalPanelLinksTest {
    private val panelUrl = "http://192.168.1.101:43007/CurrentPanelKey/"

    @Test
    fun commandUsesTheCurrentPortWithoutThePanelPath() {
        assertEquals("cloudflared tunnel --url http://192.168.1.101:43007", portalTunnelCommand(panelUrl))
    }

    @Test
    fun pastedTunnelLogBuildsAnHttpsLinkWithTheCurrentSessionKey() {
        val output = "INF | https://test-room.trycloudflare.com/OldKey/ |\nhttps://developers.cloudflare.com/tunnel/"
        assertEquals("https://test-room.trycloudflare.com/CurrentPanelKey/", portalPublicLink(panelUrl, output))
    }

    @Test
    fun rejectsUnexpectedDomainsSchemesAndCredentials() {
        assertNull(portalPublicLink(panelUrl, "https://test-room.trycloudflare.com.example.org"))
        assertNull(portalPublicLink(panelUrl, "http://test-room.trycloudflare.com"))
        assertNull(portalPublicLink(panelUrl, "https://someone@test-room.trycloudflare.com"))
    }
}
