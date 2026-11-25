package ir.irangold.core

/** Score 0..100. A failed health check is always 0; otherwise 50 base + latency/speed/exit-IP/DNS bonuses. */
object Scorer {
    fun score(r: HealthReport): Int {
        if (!r.ok) return 0
        var s = 50
        val lat = r.latencyMs
        if (lat != null) s += when {
            lat <= 300 -> 20
            lat <= 800 -> 14
            lat <= 1500 -> 8
            lat <= 3000 -> 4
            else -> 0
        }
        val sp = r.speedKbps
        if (sp != null) s += when {
            sp >= 4000 -> 20
            sp >= 1500 -> 14
            sp >= 500 -> 9
            sp >= 100 -> 4
            else -> 0
        }
        if (r.exitIp != null) s += 5
        if (r.dnsOk) s += 5
        return s.coerceIn(0, 100)
    }
}
