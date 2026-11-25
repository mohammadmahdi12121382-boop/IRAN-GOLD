package ir.irangold.app

import android.content.Context
import ir.irangold.core.Capabilities
import ir.irangold.core.Dependency
import ir.irangold.core.EngineException
import ir.irangold.core.EngineState
import java.io.File

/**
 * Snowflake parameters, copied from the official Snowflake client sample torrc:
 *   https://raw.githubusercontent.com/keroserene/snowflake/master/client/torrc  (fetched 2026-11-25)
 * They are PUBLIC values of the Tor Project's own infrastructure (no private server of ours).
 * They can go stale; if Snowflake stops working, update them from the current Tor Browser / Snowflake release.
 */
object SnowflakeDefaults {
    const val BRIDGE_FINGERPRINT = "2B280B23E1107BB62ABFC40DDCC8824814F80A72"
    const val BROKER_URL = "https://snowflake-broker.torproject.net.global.prod.fastly.net/"
    const val FRONT_DOMAINS = "cdn.sstatic.net"
    const val ICE_SERVERS =
        "stun:stun.voip.blackberry.com:3478,stun:stun.altar.com.pl:3478,stun:stun.antisip.com:3478," +
            "stun:stun.bluesip.net:3478,stun:stun.dus.net:3478,stun:stun.epygi.com:3478," +
            "stun:stun.sonetel.com:3478,stun:stun.sonetel.net:3478,stun:stun.stunprotocol.org:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.voys.nl:3478"
}

/**
 * Tor (BSD-3, libtor.so from the tor-android AAR) over Snowflake (IPtProxy, MIT).
 * Needs nothing from the user: no server, no account, no payment.
 */
class TorEngine(private val ctx: Context) : ProcessEngine() {
    override val id = "tor"
    override val displayName = "Tor + Snowflake"

    private val bin: File get() = File(ctx.applicationInfo.nativeLibraryDir, "libtor.so")
    private var snowflake: SnowflakeBridge? = null

    override fun capabilities() = Capabilities(tcp = true, udp = false, dependency = Dependency.INDEPENDENT)

    override fun isAvailable() = unavailableReason() == null

    override fun unavailableReason(): String? = if (!bin.exists()) "فایل اجرایی Tor در برنامه نیست" else null

    override fun start() {
        stop()
        state = EngineState.STARTING
        try {
            val sf = SnowflakeBridge(ctx).also { snowflake = it }
            val snowPort = sf.start()
            port = NetUtil.freePort()
            val dataDir = File(ctx.filesDir, "tor").apply { mkdirs() }
            val torrc = File(dataDir, "torrc")
            torrc.writeText(
                """
                DataDirectory ${File(dataDir, "data").absolutePath}
                SocksPort 127.0.0.1:$port
                ClientOnly 1
                AvoidDiskWrites 1
                SafeLogging 1
                Log notice stdout
                UseBridges 1
                ClientTransportPlugin snowflake socks5 127.0.0.1:$snowPort
                Bridge snowflake 192.0.2.3:1 ${SnowflakeDefaults.BRIDGE_FINGERPRINT}
                """.trimIndent() + "\n",
            )
            val boot = java.util.concurrent.atomic.AtomicBoolean(false)
            val p = ProcessBuilder(bin.absolutePath, "-f", torrc.absolutePath)
                .directory(dataDir).redirectErrorStream(true).start()
            proc = p
            pump(p) { line -> if (line.contains("Bootstrapped 100%")) boot.set(true) }
            waitForPort(120_000) { boot.get() }
            state = EngineState.RUNNING
        } catch (e: Exception) {
            val msg = e.message
            stop()
            state = EngineState.FAILED
            throw if (e is EngineException) e else EngineException(msg ?: "Tor اجرا نشد", e)
        }
    }

    override fun afterStop() {
        try { snowflake?.stop() } catch (_: Exception) { }
        snowflake = null
    }
}
