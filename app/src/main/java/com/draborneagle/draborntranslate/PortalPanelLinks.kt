// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import java.net.URI
import java.util.Locale

private val quickTunnelHost = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.trycloudflare\\.com")
private val quickTunnelUrl = Regex("https://[^\\s|]+", RegexOption.IGNORE_CASE)

internal fun portalTunnelCommand(panelUrl: String): String {
    val panel = URI(panelUrl)
    return "cloudflared tunnel --url http://${panel.host}:${panel.port}"
}

/** Accepts the printed HTTPS address or a pasted Termux log. The current session always supplies the path. */
internal fun portalPublicLink(panelUrl: String, tunnelText: String): String? {
    val addresses = quickTunnelUrl.findAll(tunnelText).map { it.value }.toList().ifEmpty { listOf(tunnelText.trim()) }
    return addresses.asReversed().firstNotNullOfOrNull { address ->
        val parsed = runCatching { URI(address) }.getOrNull() ?: return@firstNotNullOfOrNull null
        val host = parsed.host?.lowercase(Locale.ROOT) ?: return@firstNotNullOfOrNull null
        if (!parsed.scheme.equals("https", ignoreCase = true) || !quickTunnelHost.matches(host) ||
            parsed.userInfo != null || parsed.port != -1) return@firstNotNullOfOrNull null
        "https://$host${URI(panelUrl).rawPath}"
    }
}
