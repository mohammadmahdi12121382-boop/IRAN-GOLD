package ir.irangold.core

enum class RepairStep { HEALTH_CHECK, RESTART_ENGINE, RETRY, SWITCH_ENGINE, REBUILD_ROUTE, EXHAUSTED }

/** Escalating repair: each consecutive failure moves one rung up; a healthy check resets to the bottom. */
class RepairLadder {
    private var idx = 0
    private val order = listOf(
        RepairStep.HEALTH_CHECK, RepairStep.RESTART_ENGINE, RepairStep.RETRY,
        RepairStep.SWITCH_ENGINE, RepairStep.REBUILD_ROUTE,
    )

    fun reset() { idx = 0 }

    /** Step to perform after the latest failure. */
    fun next(): RepairStep {
        if (idx >= order.size) return RepairStep.EXHAUSTED
        return order[idx++]
    }
}
