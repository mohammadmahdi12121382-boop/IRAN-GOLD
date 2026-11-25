package ir.irangold.core

/** Tiny persistence seam so :core stays pure JVM (the app backs it with SharedPreferences). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class MemoryStore : KeyValueStore {
    private val m = HashMap<String, String>()
    override fun get(key: String) = synchronized(m) { m[key] }
    override fun put(key: String, value: String) { synchronized(m) { m[key] = value } }
}

/**
 * Remembers, per network fingerprint (no SSID / location), the exponentially-smoothed score of each engine,
 * so on a known network the historically best engine is tried first.
 */
class NetworkMemory(private val store: KeyValueStore, private val alpha: Double = 0.5) {
    private fun key(net: String) = "netmem.$net"

    fun scores(net: String): Map<String, Int> {
        val raw = store.get(key(net)) ?: return emptyMap()
        return try {
            Json.obj(Json.parse(raw))?.mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toInt() } }?.toMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun record(net: String, engineId: String, score: Int) {
        val cur = HashMap(scores(net))
        val old = cur[engineId]
        cur[engineId] = if (old == null) score else (old * (1 - alpha) + score * alpha).toInt()
        store.put(key(net), Json.stringify(LinkedHashMap<String, Any?>(cur.mapValues { it.value.toLong() })))
    }

    /** Engine ids ordered best-first for this network; unknown engines keep their given order after known ones. */
    fun rank(net: String, ids: List<String>): List<String> {
        val s = scores(net)
        return ids.withIndex().sortedWith(compareByDescending<IndexedValue<String>> { s[it.value] ?: -1 }.thenBy { it.index })
            .map { it.value }
    }
}
