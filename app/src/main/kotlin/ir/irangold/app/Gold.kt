package ir.irangold.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.irangold.core.ConfigException
import ir.irangold.core.Controller
import ir.irangold.core.ControllerListener
import ir.irangold.core.DataPlane
import ir.irangold.core.Engine
import ir.irangold.core.NetworkMemory
import ir.irangold.core.Phase
import ir.irangold.core.ProxyEndpoint
import ir.irangold.core.Snapshot
import ir.irangold.core.VlessUri
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Process-wide app state shared by the UI and the VpnService (single user, single session). */
object Gold {
    lateinit var appCtx: Context
    lateinit var prefs: Prefs
    lateinit var secrets: SecretStore
    lateinit var engines: List<Engine>

    @Volatile var directIp: String? = null
    @Volatile private var controller: Controller? = null
    @Volatile private var stopRequested = false

    var snapshot by mutableStateOf(Snapshot(Phase.IDLE, null, null, 0, "قطع", null))
        private set
    var importSummary by mutableStateOf<String?>(null)
        private set
    var killSwitch by mutableStateOf(true)
    var engineMode by mutableStateOf("auto")

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = Prefs(appCtx)
        secrets = SecretStore(appCtx)
        engines = listOf(XrayEngine(appCtx, secrets), TorEngine(appCtx, secrets))
        killSwitch = prefs.killSwitch
        engineMode = prefs.engineMode
        refreshImportSummary()
        bridgeCount = secrets.get(TorEngine.BRIDGES_KEY)?.lines()?.size ?: 0
    }

    fun updateKillSwitch(v: Boolean) { killSwitch = v; prefs.killSwitch = v }
    fun updateEngineMode(v: String) { engineMode = v; prefs.engineMode = v }

    private fun refreshImportSummary() {
        importSummary = secrets.get(XrayEngine.CONFIG_KEY)?.let {
            try { XrayEngine.validate(it) } catch (_: Exception) { null }
        }
    }

    /** Throws ConfigException (Persian message) for anything invalid or unsafe; stores nothing in that case. */
    fun importConfig(text: String) {
        var body = text.trim()
        if (body.startsWith("https://", ignoreCase = true)) body = fetchSubscription(body)
        else if (body.startsWith("http://", ignoreCase = true)) throw ConfigException("فقط لینک https پذیرفته می‌شود")
        val list = XrayEngine.candidates(body)
        XrayEngine.validate(body)
        secrets.put(XrayEngine.CONFIG_KEY, if (body.startsWith("{")) body else list.joinToString("\n"))
        refreshImportSummary()
    }

    /** Downloads a subscription (https only, normal certificate checks, 512 KB cap) and decodes base64 if needed. */
    private fun fetchSubscription(url: String): String {
        val raw = try {
            val c = URL(url).openConnection() as HttpsURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.inputStream.use { it.readNBytes(512 * 1024 + 1) }
        } catch (e: Exception) {
            throw ConfigException("دریافت لینک اشتراک ناموفق بود")
        }
        if (raw.size > 512 * 1024) throw ConfigException("پاسخ لینک اشتراک خیلی بزرگ است")
        val text = String(raw, Charsets.UTF_8).trim()
        if (text.contains("://")) return text
        return try {
            String(android.util.Base64.decode(text, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            throw ConfigException("فرمت لینک اشتراک شناخته نشد")
        }
    }

    // --- Tor bridges (obfs4), obtained by the user from bridges.torproject.org / @GetBridgesBot / bridges@torproject.org
    private val OBFS4 = Regex("^(?:Bridge\\s+)?obfs4\\s+\\S+:\\d{1,5}\\s+[A-Fa-f0-9]{40}\\s+cert=[A-Za-z0-9+/=]+(?:\\s+iat-mode=[0-2])?$")

    fun importBridges(text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw ConfigException("بریجی وارد نشده است")
        if (lines.size > 6) throw ConfigException("حداکثر ۶ بریج")
        for (l in lines) if (!OBFS4.matches(l)) throw ConfigException("فرمت بریج obfs4 معتبر نیست (باید مثل: obfs4 IP:PORT FINGERPRINT cert=... iat-mode=0)")
        secrets.put(TorEngine.BRIDGES_KEY, lines.joinToString("\n") { it.removePrefix("Bridge ").trim() })
        bridgeCount = lines.size
    }

    fun clearBridges() { secrets.remove(TorEngine.BRIDGES_KEY); bridgeCount = 0 }
    var bridgeCount by mutableStateOf(0)

    fun clearImport() { secrets.remove(XrayEngine.CONFIG_KEY); refreshImportSummary() }

    fun supportsUdp(p: ProxyEndpoint): Boolean =
        engines.firstOrNull { it.localProxy() == p }?.capabilities()?.udp == true

    @Volatile private var service: GoldVpnService? = null
    fun attach(s: GoldVpnService) { service = s }

    fun requestStop() {
        stopRequested = true
        controller?.cancel()
    }

    private fun publish(s: Snapshot) { snapshot = s }

    /** Live progress text while an engine is starting (e.g. Tor bootstrap %). */
    fun progress(msg: String) {
        val cur = snapshot
        if (cur.phase == Phase.CONNECTING) snapshot = cur.copy(message = msg)
    }

    /** Runs on the service's worker thread until the user disconnects. */
    fun runSession(plane: DataPlane) {
        stopRequested = false
        directIp = fetchDirectIp()
        val chosen = when (engineMode) {
            "xray" -> engines.filter { it.id == "xray" }
            "tor" -> engines.filter { it.id == "tor" }
            else -> engines
        }
        val ctl = Controller(
            chosen, NetworkMemory(prefs), plane, { NetUtil.networkId(appCtx) }, { killSwitch },
            object : ControllerListener { override fun onSnapshot(s: Snapshot) { publish(s) } },
        )
        controller = ctl
        try {
            if (ctl.connect()) {
                while (!stopRequested) {
                    sleepInterruptibly(20_000)
                    if (stopRequested) break
                    ctl.tick()
                }
            } else {
                // Kill switch ON keeps the TUN blocking; wait for the user instead of leaking.
                while (!stopRequested && killSwitch) sleepInterruptibly(1_000)
            }
        } finally {
            ctl.disconnect()
            controller = null
        }
    }

    private fun sleepInterruptibly(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!stopRequested && System.currentTimeMillis() < end) Thread.sleep(250)
    }

    /** Direct (pre-VPN) public IP, used only to detect "traffic is not going through the engine". Best effort. */
    private fun fetchDirectIp(): String? = try {
        val c = URL("https://api.ipify.org").openConnection() as HttpsURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        val ip = c.inputStream.bufferedReader().readText().trim()
        if (VlessUri.isIp(ip)) ip else null
    } catch (_: Exception) {
        null
    }
}
