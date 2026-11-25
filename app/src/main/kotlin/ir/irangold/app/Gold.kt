package ir.irangold.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        engines = listOf(XrayEngine(appCtx, secrets), TorEngine(appCtx))
        killSwitch = prefs.killSwitch
        engineMode = prefs.engineMode
        refreshImportSummary()
    }

    fun setKillSwitch(v: Boolean) { killSwitch = v; prefs.killSwitch = v }
    fun setEngineMode(v: String) { engineMode = v; prefs.engineMode = v }

    private fun refreshImportSummary() {
        importSummary = secrets.get(XrayEngine.CONFIG_KEY)?.let {
            try { XrayEngine.validate(it) } catch (_: Exception) { null }
        }
    }

    /** Throws ConfigException (Persian message) for anything invalid or unsafe; stores nothing in that case. */
    fun importConfig(text: String) {
        XrayEngine.validate(text)
        secrets.put(XrayEngine.CONFIG_KEY, text.trim())
        refreshImportSummary()
    }

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
