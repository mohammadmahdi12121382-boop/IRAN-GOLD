package ir.irangold.core

import java.net.URLDecoder

/** Thrown for any invalid / unsupported / unsafe imported configuration. Message is Persian (shown in UI). */
class ConfigException(message: String) : Exception(message)

data class VlessConfig(
    val uuid: String,
    val host: String,
    val port: Int,
    val flow: String,
    val security: String, // none | tls | reality
    val sni: String,
    val fingerprint: String,
    val publicKey: String,
    val shortId: String,
    val spiderX: String,
    val network: String, // tcp | raw | ws | grpc | httpupgrade | xhttp
    val path: String,
    val hostHeader: String,
    val serviceName: String,
    val alpn: List<String>,
    val name: String,
)

object VlessUri {
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val KEY_RE = Regex("^[A-Za-z0-9_-]{43}$")
    private val SID_RE = Regex("^([0-9a-fA-F]{2}){0,8}$")
    private val HOST_RE = Regex("^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$")
    private val IPV4_RE = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val IPV6_RE = Regex("^[0-9A-Fa-f:.]+$")
    private val NETWORKS = setOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp")
    private val FINGERPRINTS = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized",
    )

    fun isIp(host: String): Boolean = IPV4_RE.matches(host) || (host.contains(':') && IPV6_RE.matches(host))

    fun parse(input: String): VlessConfig {
        val text = input.trim()
        if (!text.regionMatches(0, "vless://", 0, 8, ignoreCase = true)) {
            throw ConfigException("این لینک VLESS نیست (باید با vless:// شروع شود)")
        }
        var rest = text.substring(8)
        var name = ""
        rest.indexOf('#').let { i ->
            if (i >= 0) {
                name = decode(rest.substring(i + 1))
                rest = rest.substring(0, i)
            }
        }
        var query = ""
        rest.indexOf('?').let { i ->
            if (i >= 0) {
                query = rest.substring(i + 1)
                rest = rest.substring(0, i)
            }
        }
        val at = rest.lastIndexOf('@')
        if (at <= 0) throw ConfigException("شناسه کاربر (UUID) در لینک نیست")
        val uuid = decode(rest.substring(0, at))
        if (!UUID_RE.matches(uuid)) throw ConfigException("UUID معتبر نیست")
        val (host, port) = splitHostPort(rest.substring(at + 1).trimEnd('/'))
        val q = parseQuery(query)

        // Safety: never accept configs that switch certificate verification off.
        for (k in listOf("allowinsecure", "insecure")) {
            val v = q[k]?.lowercase()
            if (v == "1" || v == "true") throw ConfigException("کانفیگ‌هایی که تأیید گواهی را خاموش می‌کنند (allowInsecure) پذیرفته نمی‌شوند")
        }
        val enc = q["encryption"] ?: "none"
        if (enc != "none") throw ConfigException("فقط encryption=none پشتیبانی می‌شود")

        val security = (q["security"] ?: "none").lowercase()
        if (security !in setOf("none", "tls", "reality")) throw ConfigException("security=$security پشتیبانی نمی‌شود")
        val network = (q["type"] ?: "tcp").lowercase()
        if (network !in NETWORKS) throw ConfigException("نوع انتقال (type=$network) پشتیبانی نمی‌شود")
        if ((network == "tcp" || network == "raw") && (q["headertype"] ?: "none") !in setOf("none", "")) {
            throw ConfigException("headerType غیر از none پشتیبانی نمی‌شود")
        }

        val flow = q["flow"] ?: ""
        if (flow.isNotEmpty()) {
            if (flow != "xtls-rprx-vision") throw ConfigException("flow=$flow پشتیبانی نمی‌شود")
            if (security == "none" || (network != "tcp" && network != "raw")) {
                throw ConfigException("flow=xtls-rprx-vision فقط با TCP و TLS/REALITY کار می‌کند")
            }
        }

        val fp = (q["fp"] ?: "chrome").lowercase().ifEmpty { "chrome" }
        if (fp !in FINGERPRINTS) throw ConfigException("fingerprint (fp=$fp) نامعتبر است")
        var sni = q["sni"] ?: q["servername"] ?: ""
        var pbk = ""
        var sid = ""
        var spx = ""
        val alpn = (q["alpn"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

        when (security) {
            "reality" -> {
                if (sni.isEmpty()) throw ConfigException("برای REALITY مقدار sni لازم است")
                pbk = q["pbk"] ?: ""
                if (!KEY_RE.matches(pbk)) throw ConfigException("کلید عمومی REALITY (pbk) معتبر نیست")
                sid = q["sid"] ?: ""
                if (!SID_RE.matches(sid)) throw ConfigException("shortId (sid) معتبر نیست")
                spx = q["spx"] ?: ""
            }
            "tls" -> {
                if (sni.isEmpty() && !isIp(host)) sni = host
                if (sni.isEmpty()) throw ConfigException("برای TLS با آدرس IP باید sni مشخص باشد")
            }
        }

        val path = q["path"]?.ifEmpty { null } ?: "/"
        return VlessConfig(
            uuid = uuid.lowercase(), host = host, port = port, flow = flow, security = security,
            sni = sni, fingerprint = fp, publicKey = pbk, shortId = sid.lowercase(), spiderX = spx,
            network = network, path = path, hostHeader = q["host"] ?: "", serviceName = q["servicename"] ?: "",
            alpn = alpn, name = name,
        )
    }

    private fun splitHostPort(hp: String): Pair<String, Int> {
        val host: String
        val portText: String
        if (hp.startsWith("[")) {
            val end = hp.indexOf(']')
            if (end < 0 || end + 1 >= hp.length || hp[end + 1] != ':') throw ConfigException("آدرس IPv6 نامعتبر است")
            host = hp.substring(1, end)
            portText = hp.substring(end + 2)
            if (!(host.contains(':') && IPV6_RE.matches(host))) throw ConfigException("آدرس IPv6 نامعتبر است")
        } else {
            val i = hp.lastIndexOf(':')
            if (i <= 0) throw ConfigException("پورت در لینک نیست")
            host = hp.substring(0, i)
            portText = hp.substring(i + 1)
            if (!IPV4_RE.matches(host) && !HOST_RE.matches(host)) throw ConfigException("آدرس سرور معتبر نیست")
        }
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) throw ConfigException("پورت معتبر نیست")
        return host to port
    }

    private fun parseQuery(query: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (query.isEmpty()) return out
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            val k = if (i < 0) part else part.substring(0, i)
            val v = if (i < 0) "" else part.substring(i + 1)
            out[decode(k).lowercase()] = decode(v)
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw ConfigException("لینک شامل کاراکتر نامعتبر است")
    }
}
