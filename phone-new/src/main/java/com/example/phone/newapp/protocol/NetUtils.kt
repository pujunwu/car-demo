package com.example.phone.newapp.protocol

import java.net.NetworkInterface
import java.net.SocketException

/**
 * 手机端局域网网络工具类 (自包含独立实现)
 */
object NetUtils {

    data class IpInfo(
        val interfaceName: String,
        val ipAddress: String,
        val isWifiOrHotspot: Boolean
    )

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
     * 获取首选局域网 IPv4 地址 (优先 Wi-Fi / 热点)
     */
    fun getLocalIpAddress(): String {
        val infos = getAllIpInfos()
        val wifiIp = infos.firstOrNull { it.isWifiOrHotspot }?.ipAddress
        if (wifiIp != null) return wifiIp
        return infos.firstOrNull()?.ipAddress ?: "127.0.0.1"
    }
}
