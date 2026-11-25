package ir.irangold.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import ir.irangold.core.DataPlane
import ir.irangold.core.ProxyEndpoint
import java.io.File

/**
 * THE single VpnService / TUN. Apps -> TUN -> hev-socks5-tunnel -> active engine's local SOCKS5 -> Internet.
 * Engines never create a VPN. IPv6 is captured into the TUN and dropped (leak prevention).
 */
class GoldVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null

    val plane = object : DataPlane {
        @Synchronized override fun establish(proxy: ProxyEndpoint) {
            stopHev()
            val fd = tun ?: buildTun() ?: throw IllegalStateException("VPN not permitted")
            val udp = Gold.supportsUdp(proxy)
            val cfg = File(filesDir, "hev.yaml")
            cfg.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: $TUN_ADDR
                socks5:
                  port: ${proxy.port}
                  address: ${proxy.host}
                ${if (udp) "  udp: 'udp'" else ""}
                mapdns:
                  address: $DNS_ADDR
                  port: 53
                  network: 100.64.0.0
                  netmask: 240.0.0.0
                  cache-size: 10000
                misc:
                  log-level: error
                """.trimIndent() + "\n",
            )
            // NOTE: do NOT delete cfg here: hev reads it from its own thread after this call returns.
            // (It holds only ports/addresses, no secrets.)
            val started = TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
            Thread.sleep(400)
            if (!started || !TProxyService.TProxyIsRunning()) {
                throw IllegalStateException("موتور تونل (hev) اجرا نشد")
            }
        }

        @Synchronized override fun teardown() {
            stopHev()
            closeTun()
        }

        /** Kill switch: TUN stays up, nobody reads it, so every packet is dropped. */
        @Synchronized override fun blockAll() {
            stopHev()
            if (tun == null) buildTun()
        }
    }

    private fun stopHev() {
        try { if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService() } catch (_: Throwable) { }
    }

    private fun closeTun() {
        try { tun?.close() } catch (_: Exception) { }
        tun = null
    }

    private fun buildTun(): ParcelFileDescriptor? {
        val b = Builder()
            .setSession("IRAN GOLD")
            .setMtu(1500)
            .addAddress(TUN_ADDR, 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00:1::1", 128)
            .addRoute("::", 0)
            .addDnsServer(DNS_ADDR)
        try { b.addDisallowedApplication(packageName) } catch (_: Exception) { }
        tun = b.establish()
        return tun
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startForegroundCompat()
                Gold.attach(this)
                if (worker == null) {
                    worker = Thread {
                        try {
                            Gold.runSession(plane)
                        } finally {
                            worker = null
                            plane.teardown()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }.also { it.isDaemon = true; it.start() }
                }
            }
            ACTION_DISCONNECT -> Gold.requestStop()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() { Gold.requestStop() }

    override fun onDestroy() {
        Gold.requestStop()
        plane.teardown()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "IRAN GOLD", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("IRAN GOLD")
            .setContentText("VPN فعال است")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    companion object {
        const val ACTION_CONNECT = "ir.irangold.app.CONNECT"
        const val ACTION_DISCONNECT = "ir.irangold.app.DISCONNECT"
        private const val CHANNEL = "vpn"
        const val TUN_ADDR = "198.18.0.1"
        const val DNS_ADDR = "198.18.0.2"
    }
}
