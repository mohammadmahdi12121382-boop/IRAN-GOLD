package ir.irangold.core

/** Lifecycle of one engine process/library. */
enum class EngineState { STOPPED, STARTING, RUNNING, FAILED }

/**
 * What an engine needs from the outside world.
 * INDEPENDENT        - works with nothing but the public Internet (e.g. Tor + Snowflake).
 * NEEDS_USER_CONFIG  - needs a server/config that the USER imports (e.g. Xray/VLESS).
 * The app never ships a server, a shared key, or a private endpoint.
 */
enum class Dependency { INDEPENDENT, NEEDS_USER_CONFIG }

data class ProxyEndpoint(val host: String, val port: Int)

data class Capabilities(
    val tcp: Boolean = true,
    val udp: Boolean = false,
    val dependency: Dependency,
)

/** Result of a real end-to-end probe made THROUGH the engine's own local proxy. */
data class HealthReport(
    val ok: Boolean,
    /** Stage that failed: handshake | tls-http | captive | leak | engine-down, null when ok. */
    val failedStage: String?,
    val handshakeOk: Boolean,
    val tlsHttpOk: Boolean,
    val captivePortal: Boolean,
    val exitIp: String?,
    val dnsOk: Boolean,
    val latencyMs: Long?,
    val speedKbps: Int?,
    val detail: String,
) {
    companion object {
        fun down(stage: String, detail: String) = HealthReport(
            ok = false, failedStage = stage, handshakeOk = false, tlsHttpOk = false,
            captivePortal = false, exitIp = null, dnsOk = false, latencyMs = null,
            speedKbps = null, detail = detail,
        )
    }
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Engine SPI. Engines NEVER create a VPN: they only expose a local SOCKS5 proxy
 * (see [localProxy]); the single VpnService/TUN lives in the app module.
 */
interface Engine {
    val id: String
    val displayName: String

    fun capabilities(): Capabilities

    /** True when the engine can be started now (binary present, config imported...). */
    fun isAvailable(): Boolean

    /** Human-readable (Persian) reason when [isAvailable] is false. */
    fun unavailableReason(): String?

    /** Blocks until the local SOCKS5 proxy accepts connections, or throws [EngineException]. */
    fun start()

    fun stop()

    fun status(): EngineState

    fun localProxy(): ProxyEndpoint?

    /** Real probe through the engine's own proxy. Default implementation: [HealthChecker]. */
    fun healthCheck(): HealthReport {
        val p = localProxy() ?: return HealthReport.down("engine-down", "موتور در حال اجرا نیست")
        return HealthChecker(p).run()
    }

    fun score(report: HealthReport): Int = Scorer.score(report)
}
