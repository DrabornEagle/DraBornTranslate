// SPDX-License-Identifier: AGPL-3.0-only
package com.draborneagle.draborntranslate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Binds only to a private Wi-Fi or Ethernet address, including when a VPN is the default route. */
@Suppress("DEPRECATION")
internal fun portalLanAddress(context: Context): Inet4Address? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val preferred = manager.activeNetwork
    return manager.allNetworks.sortedBy { it != preferred }.firstNotNullOfOrNull { network ->
        val capabilities = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return@firstNotNullOfOrNull null

        manager.getLinkProperties(network)?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { it.isSiteLocalAddress }
    }
}
