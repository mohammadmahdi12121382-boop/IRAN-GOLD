package ir.irangold.core

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) { passed++; println("  ok   $name") } else { failed++; println("  FAIL $name") }
}

private inline fun <reified T : Throwable> throws(name: String, block: () -> Unit) {
    try { block(); check(name, false) } catch (e: Throwable) { check(name, e is T) }
}

private const val UUID = "11111111-2222-3333-4444-555555555555"
private val PBK = "A".repeat(43)

private fun v(extra: String) = "vless://$UUID@example.com:443?$extra#name"

class FakeEngine(
    override val id: String,
    private val healthy: () -> Boolean,
    private val port: Int,
    private val fixedScore: Int = 90,
) : Engine {
    override val displayName = id
    var state = EngineState.STOPPED
    var starts = 0
    override fun capabilities() = Capabilities(dependency = Dependency.INDEPENDENT)
    override fun isAvailable() = true
    override fun unavailableReason(): String? = null
    override fun start() { starts++; state = EngineState.RUNNING }
    override fun stop() { state = EngineState.STOPPED }
    override fun status() = state
    override fun localProxy() = if (state == EngineState.RUNNING) ProxyEndpoint("127.0.0.1", port) else null
    override fun healthCheck(): HealthReport =
        if (healthy()) HealthReport(true, null, true, true, false, "9.9.9.9", true, 100, 5000, "ok")
        else HealthReport.down("tls-http", "x")
    override fun score(report: HealthReport) = if (report.ok) fixedScore else 0
}

class FakePlane : DataPlane {
    val log = ArrayList<String>()
    override fun establish(proxy: ProxyEndpoint) { log.add("up:${proxy.port}") }
    override fun teardown() { log.add("down") }
    override fun blockAll() { log.add("block") }
}

private fun testJsonAndRedactor() {
    println("Json/Redactor")
    val m = Json.obj(Json.parse("""{"a":[1,2.5,"xA\n",true,null],"b":{"c":"d"}}"""))!!
    check("json parse", (Json.arr(m["a"])!![2]) == "xA\n")
    val round = Json.parse(Json.stringify(m, true))
    check("json roundtrip", round == Json.parse(Json.stringify(m)))
    val red = Redactor.redact("link vless://$UUID@h:1?pbk=$PBK&sid=ab uuid $UUID password=secret")
    check("redact no uuid", !red.contains(UUID))
    check("redact no pbk", !red.contains(PBK))
    check("redact no password", !red.contains("secret"))
}

private fun testVless() {
    println("VlessUri")
    val ok = VlessUri.parse(v("security=reality&sni=www.example.org&pbk=$PBK&sid=abcd&flow=xtls-rprx-vision&fp=chrome&type=tcp"))
    check("reality parsed", ok.security == "reality" && ok.flow == "xtls-rprx-vision" && ok.port == 443)
    throws<ConfigException>("bad scheme") { VlessUri.parse("vmess://x") }
    throws<ConfigException>("bad uuid") { VlessUri.parse("vless://zzz@example.com:443") }
    throws<ConfigException>("allowInsecure rejected") { VlessUri.parse(v("security=tls&sni=a.com&allowInsecure=1")) }
    throws<ConfigException>("reality needs pbk") { VlessUri.parse(v("security=reality&sni=a.com")) }
    throws<ConfigException>("bad port") { VlessUri.parse("vless://$UUID@example.com:99999") }
    throws<ConfigException>("flow w/o security") { VlessUri.parse(v("flow=xtls-rprx-vision")) }
    val v6 = VlessUri.parse("vless://$UUID@[2001:db8::1]:443?security=tls&sni=a.com")
    check("ipv6 host", v6.host == "2001:db8::1")
}

private fun testConfigBuilder(outDir: File?) {
    println("XrayConfigBuilder")
    val c = VlessUri.parse(v("security=reality&sni=www.example.org&pbk=$PBK&sid=abcd&flow=xtls-rprx-vision"))
    val json = XrayConfigBuilder.fromVless(c, 10808)
    val root = Json.obj(Json.parse(json))!!
    val inb = Json.obj(Json.arr(root["inbounds"])!![0])!!
    check("only socks inbound on loopback", Json.arr(root["inbounds"])!!.size == 1 && inb["listen"] == "127.0.0.1" && inb["protocol"] == "socks")
    check("no allowInsecure in output", !json.contains("allowInsecure"))
    outDir?.let { File(it, "reality.json").writeText(json) }
    val raw = """{"outbounds":[{"protocol":"freedom"},{"protocol":"vless","settings":{"vnext":[{"address":"a.example.com","port":443,"users":[{"id":"$UUID","encryption":"none"}]}]},"streamSettings":{"security":"tls"}}]}"""
    val out = XrayConfigBuilder.fromRawJson(raw, 10809)
    check("raw json adopted", Json.obj(Json.arr(Json.obj(Json.parse(out))!!["outbounds"])!![0])!!["tag"] == "proxy")
    throws<ConfigException>("raw allowInsecure rejected") {
        XrayConfigBuilder.fromRawJson(raw.replace("\"security\":\"tls\"", "\"security\":\"tls\",\"tlsSettings\":{\"allowInsecure\":true}"), 1)
    }
    throws<ConfigException>("raw w/o proxy rejected") { XrayConfigBuilder.fromRawJson("""{"outbounds":[{"protocol":"freedom"}]}""", 1) }
}

private fun testBreakerMemoryScorer() {
    println("CircuitBreaker/NetworkMemory/Scorer")
    var now = 0L
    val b = CircuitBreaker(threshold = 2, baseCooldownMs = 1000, clock = { now })
    b.recordFailure(); check("closed after 1 failure", b.state() == BreakerState.CLOSED)
    b.recordFailure(); check("open after 2", b.state() == BreakerState.OPEN && !b.allowAttempt())
    now += 1001; check("half-open after cooldown", b.state() == BreakerState.HALF_OPEN && b.allowAttempt())
    b.recordFailure(); check("re-open on failed retest", b.state() == BreakerState.OPEN)
    now += 1500; check("longer cooldown", b.state() == BreakerState.OPEN)
    now += 600; b.recordSuccess(); check("closed after success", b.state() == BreakerState.CLOSED)

    val mem = NetworkMemory(MemoryStore())
    mem.record("n1", "tor", 70); mem.record("n1", "xray", 90)
    check("rank by memory", mem.rank("n1", listOf("tor", "xray", "new")) == listOf("xray", "tor", "new"))
    check("unknown network keeps order", mem.rank("n2", listOf("tor", "xray")) == listOf("tor", "xray"))

    check("failed = 0", Scorer.score(HealthReport.down("x", "y")) == 0)
    val good = HealthReport(true, null, true, true, false, "1.1.1.1", true, 200, 5000, "")
    check("perfect = 100", Scorer.score(good) == 100)
    check("slower is lower", Scorer.score(good.copy(latencyMs = 2000, speedKbps = 200)) < Scorer.score(good))
}

private fun testController() {
    println("Controller")
    // Xray=91, Tor=72 -> Xray active
    var xrayOk = true
    val xray = FakeEngine("xray", { xrayOk }, 1, 91)
    val tor = FakeEngine("tor", { true }, 2, 72)
    val plane = FakePlane()
    val snaps = ArrayList<Snapshot>()
    val ctl = Controller(
        listOf(xray, tor), NetworkMemory(MemoryStore()), plane, { "net" }, { true },
        object : ControllerListener { override fun onSnapshot(s: Snapshot) { snaps.add(s) } },
        breakers = mapOf("xray" to CircuitBreaker(threshold = 1), "tor" to CircuitBreaker(threshold = 1)),
    )
    check("connect ok", ctl.connect())
    check("xray active (91 >= accept)", ctl.snapshot().activeEngine == "xray" && ctl.snapshot().score == 91)
    check("tor not started (early accept)", tor.starts == 0)
    // xray dies -> ladder -> eventually switch to tor
    xrayOk = false
    var guard = 0
    while (ctl.snapshot().activeEngine != "tor" && guard++ < 8) ctl.tick()
    check("ladder switched to tor", ctl.snapshot().activeEngine == "tor" && ctl.snapshot().phase == Phase.CONNECTED)
    check("xray stopped", xray.state == EngineState.STOPPED)
    check("xray restarted on the ladder before switching", xray.starts >= 2)

    // Everything dead + kill switch ON -> blocked, not torn down
    val a = FakeEngine("a", { false }, 3)
    val plane2 = FakePlane()
    val c2 = Controller(listOf(a), NetworkMemory(MemoryStore()), plane2, { "n" }, { true },
        object : ControllerListener { override fun onSnapshot(s: Snapshot) {} })
    check("connect fails when nothing healthy", !c2.connect())
    check("kill switch blocks", plane2.log.last() == "block" && c2.snapshot().phase == Phase.FAILED)
    val plane3 = FakePlane()
    val c3 = Controller(listOf(FakeEngine("b", { false }, 3)), NetworkMemory(MemoryStore()), plane3, { "n" }, { false },
        object : ControllerListener { override fun onSnapshot(s: Snapshot) {} })
    c3.connect()
    check("kill switch OFF tears down", plane3.log.last() == "down")
}

/** Local TLS server (self-signed, SAN for the probe hosts) + a fake SOCKS5 proxy that forwards everything to it. */
private fun testHealthChecker(ksPath: String) {
    println("HealthChecker (local SOCKS5 + TLS)")
    val ks = KeyStore.getInstance("PKCS12").apply { File(ksPath).inputStream().use { load(it, "changeit".toCharArray()) } }
    val kmf = KeyManagerFactory.getInstance("SunX509").apply { init(ks, "changeit".toCharArray()) }
    val serverCtx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    val tmf = TrustManagerFactory.getInstance("PKIX").apply { init(ks) }
    val clientCtx = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }

    val tls = serverCtx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    thread(isDaemon = true) {
        while (true) {
            val c = try { tls.accept() } catch (_: Exception) { return@thread }
            thread(isDaemon = true) {
                try {
                    val inp = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val line = inp.readLine() ?: return@thread
                    while (inp.readLine()?.isNotEmpty() == true) { }
                    val path = line.split(' ')[1]
                    val out = c.getOutputStream()
                    fun send(status: String, body: ByteArray, chunked: Boolean = false) {
                        if (chunked) {
                            out.write("HTTP/1.1 $status\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                            out.write("${body.size.toString(16)}\r\n".toByteArray()); out.write(body); out.write("\r\n0\r\n\r\n".toByteArray())
                        } else {
                            out.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray()); out.write(body)
                        }
                        out.flush()
                    }
                    when {
                        path.startsWith("/generate_204") -> send("204 No Content", ByteArray(0))
                        path.startsWith("/__down") -> send("200 OK", ByteArray(300_000) { 1 })
                        else -> send("200 OK", "203.0.113.7".toByteArray(), chunked = true)
                    }
                } catch (_: Exception) {
                } finally { try { c.close() } catch (_: Exception) { } }
            }
        }
    }

    val seenDomains = java.util.Collections.synchronizedList(ArrayList<String>())
    val socks = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    thread(isDaemon = true) {
        while (true) {
            val c = try { socks.accept() } catch (_: Exception) { return@thread }
            thread(isDaemon = true) {
                try {
                    val i = c.getInputStream(); val o = c.getOutputStream()
                    i.readNBytes(3); o.write(byteArrayOf(5, 0)); o.flush()
                    val h = i.readNBytes(5)
                    val dom = String(i.readNBytes(h[4].toInt() and 0xff)); i.readNBytes(2)
                    seenDomains.add(dom)
                    val up = Socket("127.0.0.1", tls.localPort)
                    o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                    thread(isDaemon = true) { try { up.getInputStream().copyTo(o) } catch (_: Exception) { } finally { try { c.close() } catch (_: Exception) { } } }
                    try { i.copyTo(up.getOutputStream()) } catch (_: Exception) { } finally { try { up.close() } catch (_: Exception) { } }
                } catch (_: Exception) { try { c.close() } catch (_: Exception) { } }
            }
        }
    }

    val ep = ProxyEndpoint("127.0.0.1", socks.localPort)
    val rep = HealthChecker(ep, directIp = "198.51.100.1", ssl = clientCtx.socketFactory, timeoutMs = 5000).run()
    check("healthy report ok", rep.ok)
    check("exit IP parsed (chunked body)", rep.exitIp == "203.0.113.7")
    check("dns ok", rep.dnsOk)
    check("latency measured", rep.latencyMs != null)
    check("speed measured", (rep.speedKbps ?: 0) > 0)
    check("proxy was asked by DOMAIN name (remote DNS)", seenDomains.contains("www.gstatic.com") && seenDomains.contains("api.ipify.org"))
    check("score high", Scorer.score(rep) >= 80)

    val leak = HealthChecker(ep, directIp = "203.0.113.7", ssl = clientCtx.socketFactory, timeoutMs = 5000, speedTest = false).run()
    check("leak detected when exit IP == direct IP", !leak.ok && leak.failedStage == "leak")

    // Default trust store must reject the self-signed server (no MITM-style acceptance).
    val strict = HealthChecker(ep, timeoutMs = 3000, speedTest = false).run()
    check("untrusted cert rejected", !strict.ok && strict.failedStage == "tls-http")

    val dead = ServerSocket(0).also { it.close() }.localPort
    val down = HealthChecker(ProxyEndpoint("127.0.0.1", dead), timeoutMs = 1000).run()
    check("dead proxy -> handshake", !down.ok && down.failedStage == "handshake")
}

fun main(args: Array<String>) {
    // args: [keystore.p12] [outDir for generated xray configs]
    testJsonAndRedactor()
    testVless()
    testConfigBuilder(args.getOrNull(1)?.let { File(it).also { d -> d.mkdirs() } })
    testBreakerMemoryScorer()
    testController()
    if (args.isNotEmpty()) testHealthChecker(args[0]) else println("(HealthChecker test skipped: no keystore arg)")
    println("\n$passed passed, $failed failed")
    System.exit(if (failed == 0) 0 else 1)
}
