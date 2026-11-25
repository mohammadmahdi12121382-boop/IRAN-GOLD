package ir.irangold.app

import android.content.Context
import IPtProxy.Controller
import IPtProxy.IPtProxy
import IPtProxy.OnTransportEvents
import java.io.File

/**
 * Thin wrapper over IPtProxy (MIT): runs a Tor pluggable transport in-process (Snowflake or obfs4/lyrebird)
 * and exposes a local SOCKS port that Tor uses via ClientTransportPlugin.
 */
class SnowflakeBridge(ctx: Context) {
    private val stateDir = File(ctx.cacheDir, "pt").apply { mkdirs() }.absolutePath
    private var controller: Controller? = null
    private var method: String = IPtProxy.Snowflake

    private fun newController() = Controller(
        stateDir, false, false, "ERROR",
        object : OnTransportEvents {
            override fun connected(name: String?) {}
            override fun error(name: String?, error: java.lang.Exception?) {}
            override fun stopped(name: String?, error: java.lang.Exception?) {}
        },
    )

    fun start(v: SnowflakeVariant): Int {
        val c = newController()
        c.snowflakeBrokerUrl = v.broker
        c.snowflakeFrontDomains = v.fronts
        if (v.ampCache.isNotEmpty()) c.snowflakeAmpCacheUrl = v.ampCache
        c.snowflakeIceServers = SnowflakeDefaults.ICE_SERVERS
        method = IPtProxy.Snowflake
        c.start(method, "")
        controller = c
        return c.port(method).toInt()
    }

    fun startObfs4(): Int {
        val c = newController()
        method = IPtProxy.Obfs4
        c.start(method, "")
        controller = c
        return c.port(method).toInt()
    }

    fun stop() {
        controller?.stop(method)
        controller = null
    }
}
