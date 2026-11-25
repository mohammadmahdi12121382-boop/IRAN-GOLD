package ir.irangold.app

import android.content.Context
import ir.irangold.core.Capabilities
import ir.irangold.core.Dependency
import ir.irangold.core.EngineException
import ir.irangold.core.EngineState
import java.io.File

/** One Snowflake rendezvous method (how the client reaches the Tor Project broker). */
data class SnowflakeVariant(val name: String, val broker: String, val fronts: String, val ampCache: String)

/**
 * Snowflake parameters. All values are PUBLIC Tor Project infrastructure / documented workarounds; none is ours.
 * Sources (read 2026-11-25):
 *  - Tor forum "Snowflake and Conjure inaccessible due to CDN77 blockage, try this workaround"
 *    https://forum.torproject.org/t/snowflake-and-conjure-inaccessible-due-to-cnd77-blockage-try-this-workaround/20650
 *    (AMP-cache bridge line and the netlify/vuejs.org front)
 *  - Snowflake sample torrc https://raw.githubusercontent.com/keroserene/snowflake/master/client/torrc (fastly front, older)
 *  - STUN additions: tor-browser-build issue 41775 (2026 STUN list update)
 * They go stale. The engine tries the variants in order and keeps the first that bootstraps.
 */
object SnowflakeDefaults {
    const val BRIDGE_FINGERPRINT = "2B280B23E1107BB62ABFC40DDCC8824814F80A72"
    const val ICE_SERVERS =
        "stun:stun.voip.blackberry.com:3478,stun:stun.altar.com.pl:3478,stun:stun.antisip.com:3478," +
            "stun:stun.bluesip.net:3478,stun:stun.dus.net:3478,stun:stun.epygi.com:3478," +
            "stun:stun.sonetel.com:3478,stun:stun.sonetel.net:3478,stun:stun.stunprotocol.org:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.voys.nl:3478," +
            "stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478"
    val VARIANTS = listOf(
        SnowflakeVariant("AMP cache", "https://snowflake-broker.torproject.net/", "www.google.com", "https://cdn.ampproject.org/"),
        SnowflakeVariant("Netlify front", "https://bespoke-strudel-c243cc.netlify.app", "vuejs.org", ""),
        SnowflakeVariant("Fastly front", "https://snowflake-broker.torproject.net.global.prod.fastly.net/", "cdn.sstatic.net", ""),
    )
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
        var lastErr = "Tor اجرا نشد"
        for ((i, v) in SnowflakeDefaults.VARIANTS.withIndex()) {
            Gold.progress("Tor: روش ${i + 1} از ${SnowflakeDefaults.VARIANTS.size} (${v.name})…")
            try {
                launch(v)
                state = EngineState.RUNNING
                return
            } catch (e: Exception) {
                lastErr = "${v.name}: ${e.message}"
                stopProcessOnly()
            }
        }
        stop()
        state = EngineState.FAILED
        throw EngineException(lastErr)
    }

    private fun stopProcessOnly() {
        val p = proc
        proc = null
        p?.destroy()
        try { p?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { }
        try { snowflake?.stop() } catch (_: Exception) { }
        snowflake = null
    }

    private fun launch(v: SnowflakeVariant) {
        val sf = SnowflakeBridge(ctx).also { snowflake = it }
        val snowPort = sf.start(v)
        port = NetUtil.freePort()
        val dataDir = File(ctx.filesDir, "tor").apply { mkdirs() }
        File(dataDir, "data").deleteRecursively() // fresh state per attempt
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
        val pct = Regex("Bootstrapped (\\d+)%")
        pump(p) { line ->
            pct.find(line)?.let { m ->
                Gold.progress("Tor (${v.name}): ${m.groupValues[1]}%")
                if (m.groupValues[1] == "100") boot.set(true)
            }
        }
        waitForPort(90_000) { boot.get() }
    }

    override fun afterStop() {
        try { snowflake?.stop() } catch (_: Exception) { }
        snowflake = null
    }
}
