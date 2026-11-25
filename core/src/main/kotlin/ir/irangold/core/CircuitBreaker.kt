package ir.irangold.core

enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

/**
 * Per-engine circuit breaker. After [threshold] consecutive failures it OPENs (engine temporarily disabled);
 * after [cooldownMs] it becomes HALF_OPEN (one retest allowed); success CLOSEs it, failure re-OPENs with longer cooldown.
 */
class CircuitBreaker(
    private val threshold: Int = 2,
    private val baseCooldownMs: Long = 60_000,
    private val maxCooldownMs: Long = 15 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var failures = 0
    private var openedAt = 0L
    private var trips = 0
    private var open = false

    fun state(): BreakerState = synchronized(lock) {
        when {
            !open -> BreakerState.CLOSED
            clock() - openedAt >= cooldown() -> BreakerState.HALF_OPEN
            else -> BreakerState.OPEN
        }
    }

    fun allowAttempt(): Boolean = state() != BreakerState.OPEN

    fun recordSuccess() = synchronized(lock) {
        failures = 0; open = false; trips = 0
    }

    fun recordFailure() = synchronized(lock) {
        failures++
        if (open || failures >= threshold) {
            if (!open || clock() - openedAt >= cooldown()) trips++
            open = true
            openedAt = clock()
        }
    }

    fun remainingCooldownMs(): Long = synchronized(lock) {
        if (!open) 0 else maxOf(0, cooldown() - (clock() - openedAt))
    }

    private fun cooldown(): Long = minOf(maxCooldownMs, baseCooldownMs shl minOf(maxOf(trips - 1, 0), 8))
}
