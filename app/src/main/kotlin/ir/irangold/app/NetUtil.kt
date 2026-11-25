package ir.irangold.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

object NetUtil {
    fun freePort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    fun portOpen(port: Int, timeoutMs: Int = 300): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    /**
     * Anonymous fingerprint of the physical network (NOT the VPN): transport + gateway (Wi-Fi) or operator MCC/MNC (cellular).
     * No SSID, no location, no phone-state permission.
     */
    fun networkId(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return "none"
        val caps = cm.getNetworkCapabilities(net)
        val raw = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> {
                val gw = cm.getLinkProperties(net)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
                    ?.gateway?.hostAddress ?: "?"
                "wifi|$gw"
            }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                "cell|${tm.networkOperator}"
            }
            else -> "other"
        }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(6)
            .joinToString("") { "%02x".format(it) }
    }
}
