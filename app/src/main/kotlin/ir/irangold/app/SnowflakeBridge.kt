package ir.irangold.app

import android.content.Context
import IPtProxy.Controller
import IPtProxy.IPtProxy
import IPtProxy.OnTransportEvents
import java.io.File

/** Thin wrapper over IPtProxy (MIT): runs the Snowflake client in-process and exposes a local SOCKS port for Tor. */
class SnowflakeBridge(ctx: Context) {
    private val stateDir = File(ctx.cacheDir, "pt").apply { mkdirs() }.absolutePath
    private var controller: Controller? = null

    fun start(): Int {
        val c = Controller(
            stateDir, false, false, "ERROR",
            object : OnTransportEvents {
                override fun connected(name: String?) {}
                override fun error(name: String?, error: java.lang.Exception?) {}
                override fun stopped(name: String?, error: java.lang.Exception?) {}
            },
        )
        c.snowflakeBrokerUrl = SnowflakeDefaults.BROKER_URL
        c.snowflakeFrontDomains = SnowflakeDefaults.FRONT_DOMAINS
        c.snowflakeIceServers = SnowflakeDefaults.ICE_SERVERS
        c.start(IPtProxy.Snowflake, "")
        controller = c
        return c.port(IPtProxy.Snowflake).toInt()
    }

    fun stop() {
        controller?.stop(IPtProxy.Snowflake)
        controller = null
    }
}
