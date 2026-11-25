package ir.irangold.core

enum class Phase { IDLE, CONNECTING, CONNECTED, REPAIRING, FAILED }

data class Snapshot(
    val phase: Phase,
    val activeEngine: String?,
    val report: HealthReport?,
    val score: Int,
    val message: String,
    val connectedSinceMs: Long?,
)

/** The single TUN/VpnService lives in the app; the controller only tells it which local SOCKS5 to feed. */
interface DataPlane {
    fun establish(proxy: ProxyEndpoint)
    fun teardown()

    /** Kill switch: keep the TUN up but drop all traffic. */
    fun blockAll()
}

interface ControllerListener {
    fun onSnapshot(s: Snapshot)
}

class Controller(
    private val engines: List<Engine>,
    private val memory: NetworkMemory,
    private val dataPlane: DataPlane,
    private val networkId: () -> String,
    private val killSwitch: () -> Boolean,
    private val listener: ControllerListener,
    private val breakers: Map<String, CircuitBreaker> = engines.associate { it.id to CircuitBreaker() },
    private val acceptScore: Int = 80,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val ladder = RepairLadder()

    @Volatile private var cancelled = false
    private var active: Engine? = null
    private var since: Long? = null
    private var last: Snapshot = Snapshot(Phase.IDLE, null, null, 0, "قطع", null)

    fun snapshot(): Snapshot = last

    private fun publish(phase: Phase, engine: Engine?, report: HealthReport?, score: Int, msg: String) {
        last = Snapshot(phase, engine?.id, report, score, msg, if (phase == Phase.CONNECTED || phase == Phase.REPAIRING) since else null)
        listener.onSnapshot(last)
    }

    /** Stops engines quickly; safe to call from any thread (intentionally not synchronized). */
    fun cancel() {
        cancelled = true
        for (e in engines) try { e.stop() } catch (_: Exception) { }
    }

    /** Blocking. Probes engines best-first, keeps the best, builds the route. */
    fun connect(): Boolean = synchronized(lock) {
        cancelled = false
        ladder.reset()
        publish(Phase.CONNECTING, null, null, 0, "در حال انتخاب بهترین موتور…")
        val net = networkId()
        val order = memory.rank(net, engines.map { it.id }).mapNotNull { id -> engines.firstOrNull { it.id == id } }
        var best: Engine? = null
        var bestReport: HealthReport? = null
        var bestScore = -1
        for (e in order) {
            if (cancelled) return false
            if (!e.isAvailable()) continue
            val br = breakers.getValue(e.id)
            if (!br.allowAttempt()) continue
            publish(Phase.CONNECTING, e, null, 0, "آزمایش ${e.displayName}…")
            val (rep, sc) = probe(e, br, net)
            if (cancelled) { safeStop(e); return false }
            if (rep != null && rep.ok && sc > bestScore) {
                best?.let { safeStop(it) }
                best = e; bestReport = rep; bestScore = sc
                if (sc >= acceptScore) break
            } else {
                safeStop(e)
            }
        }
        // Make sure only the winner is running.
        for (e in engines) if (e !== best) safeStop(e)
        val winner = best
        if (winner == null) {
            failClosed("هیچ موتوری سالم نبود")
            return false
        }
        active = winner
        since = clock()
        dataPlane.establish(winner.localProxy()!!)
        publish(Phase.CONNECTED, winner, bestReport, bestScore, "متصل از طریق ${winner.displayName}")
        true
    }

    private fun probe(e: Engine, br: CircuitBreaker, net: String): Pair<HealthReport?, Int> {
        return try {
            if (e.status() != EngineState.RUNNING) e.start()
            val rep = e.healthCheck()
            val sc = e.score(rep)
            memory.record(net, e.id, sc)
            if (rep.ok) br.recordSuccess() else br.recordFailure()
            rep to sc
        } catch (ex: Exception) {
            br.recordFailure()
            memory.record(net, e.id, 0)
            null to 0
        }
    }

    /** Called periodically while connected. Performs a health check and, on failure, climbs the repair ladder. */
    fun tick(): Unit = synchronized(lock) {
        val e = active ?: return
        if (cancelled) return
        val rep = try { e.healthCheck() } catch (_: Exception) { HealthReport.down("engine-down", "خطا در بررسی سلامت") }
        if (rep.ok) {
            ladder.reset()
            val sc = e.score(rep)
            memory.record(networkId(), e.id, sc)
            breakers.getValue(e.id).recordSuccess()
            publish(Phase.CONNECTED, e, rep, sc, "متصل از طریق ${e.displayName}")
            return
        }
        breakers.getValue(e.id).recordFailure()
        when (val step = ladder.next()) {
            RepairStep.HEALTH_CHECK -> publish(Phase.REPAIRING, e, rep, 0, "بررسی مجدد…")
            RepairStep.RESTART_ENGINE -> {
                publish(Phase.REPAIRING, e, rep, 0, "راه‌اندازی مجدد ${e.displayName}…")
                safeStop(e)
                try { e.start() } catch (_: Exception) { }
            }
            RepairStep.RETRY -> publish(Phase.REPAIRING, e, rep, 0, "تلاش دوباره…")
            RepairStep.SWITCH_ENGINE -> {
                publish(Phase.REPAIRING, e, rep, 0, "تعویض موتور…")
                safeStop(e)
                active = null
                if (!reconnectExcluding(e)) return
            }
            RepairStep.REBUILD_ROUTE -> {
                publish(Phase.REPAIRING, e, rep, 0, "بازسازی مسیر…")
                dataPlane.teardown()
                val p = e.localProxy()
                if (p != null) dataPlane.establish(p)
            }
            RepairStep.EXHAUSTED -> {
                safeStop(e)
                active = null
                failClosed("همه مراحل ترمیم ناموفق بود")
            }
        }
    }

    private fun reconnectExcluding(bad: Engine): Boolean {
        val net = networkId()
        val order = memory.rank(net, engines.map { it.id }).mapNotNull { id -> engines.firstOrNull { it.id == id } }
        for (e in order) {
            if (e === bad || !e.isAvailable()) continue
            val br = breakers.getValue(e.id)
            if (!br.allowAttempt()) continue
            val (rep, sc) = probe(e, br, net)
            if (rep != null && rep.ok) {
                active = e
                ladder.reset()
                dataPlane.teardown()
                dataPlane.establish(e.localProxy()!!)
                publish(Phase.CONNECTED, e, rep, sc, "متصل از طریق ${e.displayName}")
                return true
            }
            safeStop(e)
        }
        failClosed("موتور جایگزین سالمی پیدا نشد")
        return false
    }

    fun disconnect(): Unit = synchronized(lock) {
        for (e in engines) safeStop(e)
        active = null
        since = null
        dataPlane.teardown()
        publish(Phase.IDLE, null, null, 0, "قطع")
    }

    /** Kill switch ON: keep the TUN and drop everything (no leak). OFF: tear down so the normal network returns. */
    private fun failClosed(msg: String) {
        if (killSwitch()) dataPlane.blockAll() else dataPlane.teardown()
        publish(Phase.FAILED, null, null, 0, if (killSwitch()) "$msg — ترافیک مسدود شد (کیل‌سوییچ)" else msg)
    }

    private fun safeStop(e: Engine) {
        try { e.stop() } catch (_: Exception) { }
    }
}
