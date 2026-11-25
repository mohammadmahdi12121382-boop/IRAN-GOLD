package ir.irangold.app

import ir.irangold.core.Engine
import ir.irangold.core.EngineException
import ir.irangold.core.EngineState
import ir.irangold.core.HealthChecker
import ir.irangold.core.HealthReport
import ir.irangold.core.ProxyEndpoint
import ir.irangold.core.Redactor

/**
 * Base for engines that are separate native processes (Xray, Tor). The process runs inside the app's own UID, which the
 * VpnService excludes (addDisallowedApplication), so an engine's own sockets never loop back into the TUN.
 */
abstract class ProcessEngine : Engine {
    @Volatile protected var state = EngineState.STOPPED
    @Volatile protected var port = 0
    @Volatile protected var proc: Process? = null
    private val tail = ArrayDeque<String>()

    override fun status(): EngineState {
        val p = proc
        if (state == EngineState.RUNNING && (p == null || !p.isAlive)) state = EngineState.FAILED
        return state
    }

    override fun localProxy(): ProxyEndpoint? = if (status() == EngineState.RUNNING) ProxyEndpoint("127.0.0.1", port) else null

    override fun healthCheck(): HealthReport {
        val p = localProxy() ?: return HealthReport.down("engine-down", "موتور در حال اجرا نیست")
        return HealthChecker(p, directIp = Gold.directIp).run()
    }

    protected fun pump(p: Process, onLine: (String) -> Unit = {}) {
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    // Only a redacted tail is kept in memory (for error messages); nothing is written to disk/logcat.
                    synchronized(tail) {
                        tail.addLast(Redactor.redact(line).take(300))
                        while (tail.size > 20) tail.removeFirst()
                    }
                    onLine(line)
                }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true }.start()
    }

    protected fun lastLines(): String = synchronized(tail) { tail.takeLast(3).joinToString(" | ") }

    protected fun waitForPort(timeoutMs: Long, alsoRequire: () -> Boolean = { true }) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val p = proc
            if (p == null || !p.isAlive) throw EngineException("فرایند موتور متوقف شد. ${lastLines()}")
            if (NetUtil.portOpen(port) && alsoRequire()) return
            Thread.sleep(150)
        }
        throw EngineException("موتور در زمان مقرر آماده نشد. ${lastLines()}")
    }

    override fun stop() {
        val p = proc
        proc = null
        if (p != null) {
            p.destroy()
            try {
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            } catch (_: InterruptedException) {
                p.destroyForcibly()
            }
        }
        afterStop()
        state = EngineState.STOPPED
    }

    protected open fun afterStop() {}
}
