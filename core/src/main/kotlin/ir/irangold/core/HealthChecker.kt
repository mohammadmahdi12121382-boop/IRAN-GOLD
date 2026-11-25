package ir.irangold.core

import javax.net.ssl.SSLSocketFactory

/**
 * Real end-to-end probe THROUGH the engine's local SOCKS5 proxy (not a ping):
 * handshake -> TLS+HTTP (captive-portal aware) -> exit IP (+leak check) -> remote DNS -> latency -> light speed test.
 * All endpoints are public, well-known HTTPS hosts; nothing private is assumed.
 */
class HealthChecker(
    private val proxy: ProxyEndpoint,
    private val directIp: String? = null,
    private val ssl: SSLSocketFactory = javax.net.ssl.SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val timeoutMs: Int = 10_000,
    private val speedTest: Boolean = true,
    private val generate204: List<Triple<String, Int, String>> = listOf(
        Triple("www.gstatic.com", 443, "/generate_204"),
        Triple("cp.cloudflare.com", 443, "/generate_204"),
    ),
    private val ipEcho: Triple<String, Int, String> = Triple("api.ipify.org", 443, "/"),
    private val speedUrl: Triple<String, Int, String> = Triple("speed.cloudflare.com", 443, "/__down?bytes=300000"),
) {
    private fun fetch(t: Triple<String, Int, String>, maxBody: Int = 1 shl 20): HttpResult {
        val s = Socks5.connect(proxy, t.first, t.second, timeoutMs)
        return HttpProbe.get(s, t.first, t.second, t.third, ssl, timeoutMs, maxBody)
    }

    fun run(): HealthReport {
        try {
            Socks5.greet(proxy, timeoutMs)
        } catch (e: Exception) {
            return HealthReport.down("handshake", "پراکسی محلی پاسخ نمی‌دهد")
        }

        // TLS + real HTTP response (two independent targets; either one answering 204 is enough).
        var tlsOk = false
        var captive = false
        val latencies = ArrayList<Long>()
        var lastErr = ""
        for (t in generate204) {
            try {
                val t0 = System.nanoTime()
                val r = fetch(t, 4096)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (r.status == 204) {
                    tlsOk = true
                    latencies.add(ms)
                    break
                } else if (r.status == 200 || r.status in 300..399) {
                    captive = true // a 204 endpoint that returns a page is a captive portal / interception
                    lastErr = "پاسخ غیرمنتظره ${r.status}"
                } else {
                    lastErr = "HTTP ${r.status}"
                }
            } catch (e: Exception) {
                lastErr = e.javaClass.simpleName
            }
        }
        if (!tlsOk) {
            val stage = if (captive) "captive" else "tls-http"
            return HealthReport(
                ok = false, failedStage = stage, handshakeOk = true, tlsHttpOk = false,
                captivePortal = captive, exitIp = null, dnsOk = false, latencyMs = null,
                speedKbps = null,
                detail = if (captive) "پورتال محصور (captive portal) یا دست‌کاری ترافیک: $lastErr" else "اتصال TLS/HTTP برقرار نشد: $lastErr",
            )
        }

        // Exit IP (the proxy resolves the domain remotely, so this also proves remote DNS works).
        var exitIp: String? = null
        var dnsOk = false
        try {
            val r = fetch(ipEcho, 256)
            val ip = String(r.body, Charsets.US_ASCII).trim()
            if (r.status == 200 && VlessUri.isIp(ip)) {
                exitIp = ip
                dnsOk = true
            }
        } catch (_: Exception) {
        }
        if (exitIp != null && directIp != null && exitIp == directIp) {
            return HealthReport(
                ok = false, failedStage = "leak", handshakeOk = true, tlsHttpOk = true, captivePortal = false,
                exitIp = exitIp, dnsOk = dnsOk, latencyMs = latencies.firstOrNull(), speedKbps = null,
                detail = "IP خروجی با IP مستقیم یکی است (ترافیک از موتور عبور نمی‌کند)",
            )
        }

        // Median latency of 3 small requests.
        for (i in 0 until 2) {
            try {
                val t0 = System.nanoTime()
                val r = fetch(generate204[0], 4096)
                if (r.status == 204) latencies.add((System.nanoTime() - t0) / 1_000_000)
            } catch (_: Exception) {
            }
        }
        val latency = latencies.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

        var speed: Int? = null
        if (speedTest) {
            try {
                val t0 = System.nanoTime()
                val r = fetch(speedUrl, 400_000)
                val secs = (System.nanoTime() - t0) / 1e9
                if (r.status == 200 && r.body.size > 50_000 && secs > 0) {
                    speed = ((r.body.size * 8) / 1000.0 / secs).toInt()
                }
            } catch (_: Exception) {
            }
        }

        return HealthReport(
            ok = true, failedStage = null, handshakeOk = true, tlsHttpOk = true, captivePortal = false,
            exitIp = exitIp, dnsOk = dnsOk, latencyMs = latency, speedKbps = speed, detail = "سالم",
        )
    }
}
