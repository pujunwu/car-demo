package com.example.protocol

import java.net.NetworkInterface
import java.net.SocketException

object NetUtils {

    data class IpInfo(
        val interfaceName: String,
        val ipAddress: String,
        val isWifiOrHotspot: Boolean
    )

    /**
     * Gets all valid local IPv4 addresses categorized by network interface.
     */
    fun getAllIpInfos(): List<IpInfo> {
        val list = mutableListOf<IpInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addresses = intf.inetAddresses
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr.address.size == 4) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && !hostAddress.startsWith("127.")) {
                            val name = intf.name.lowercase()
                            val isWifiOrAp = name.contains("wlan") || name.contains("ap") || name.contains("eth")
                            list.add(IpInfo(intf.name, hostAddress, isWifiOrAp))
                        }
                    }
                }
            }
        } catch (e: SocketException) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Gets the preferred IPv4 address (Wi-Fi or AP Hotspot preferred over cellular).
     */
    fun getLocalIpAddress(): String {
        val infos = getAllIpInfos()
        // Priority 1: Wi-Fi or AP Hotspot interfaces
        val wifiIp = infos.firstOrNull { it.isWifiOrHotspot }?.ipAddress
        if (wifiIp != null) return wifiIp

        // Priority 2: Any IPv4 address
        return infos.firstOrNull()?.ipAddress ?: "127.0.0.1"
    }
}
