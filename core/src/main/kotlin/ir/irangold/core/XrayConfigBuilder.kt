package ir.irangold.core

/**
 * Builds the Xray config the app runs. The ONLY inbound is a SOCKS5 server on 127.0.0.1
 * (the engine SPI contract); the only upstream is the server the USER imported.
 */
object XrayConfigBuilder {
    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria")

    fun fromVless(c: VlessConfig, socksPort: Int): String {
        val outbound = linkedMapOf<String, Any?>(
            "tag" to "proxy",
            "protocol" to "vless",
            "settings" to linkedMapOf(
                "vnext" to listOf(
                    linkedMapOf(
                        "address" to c.host,
                        "port" to c.port.toLong(),
                        "users" to listOf(
                            linkedMapOf<String, Any?>("id" to c.uuid, "encryption" to "none")
                                .also { if (c.flow.isNotEmpty()) it["flow"] = c.flow },
                        ),
                    ),
                ),
            ),
            "streamSettings" to streamSettings(c),
        )
        return build(outbound, c.host, socksPort)
    }

    /** Accepts a full Xray JSON config (e.g. exported by another tool); keeps only its first proxy outbound. */
    fun fromRawJson(text: String, socksPort: Int): String {
        val root = try {
            Json.obj(Json.parse(text))
        } catch (e: IllegalArgumentException) {
            throw ConfigException("فایل JSON معتبر نیست")
        } ?: throw ConfigException("JSON باید یک شیء (object) باشد")
        val outs = Json.arr(root["outbounds"]) ?: throw ConfigException("کانفیگ outbounds ندارد")
        val candidates = outs.mapNotNull { Json.obj(it) }
        val chosen = candidates.firstOrNull { it["tag"] == "proxy" && it["protocol"] in PROXY_PROTOCOLS }
            ?: candidates.firstOrNull { it["protocol"] in PROXY_PROTOCOLS }
            ?: throw ConfigException("هیچ outbound پراکسی (vless/vmess/trojan/shadowsocks) در فایل نیست")
        if (containsInsecure(chosen)) {
            throw ConfigException("کانفیگ‌هایی که تأیید گواهی را خاموش می‌کنند (allowInsecure) پذیرفته نمی‌شوند")
        }
        val outbound = LinkedHashMap(chosen)
        outbound["tag"] = "proxy"
        return build(outbound, serverHostOf(chosen), socksPort)
    }

    /** Server address of a parsed outbound (used only for the DNS bootstrap rule). */
    fun serverHostOf(outbound: Map<String, Any?>): String? {
        val settings = Json.obj(outbound["settings"]) ?: return null
        val first = (Json.arr(settings["vnext"]) ?: Json.arr(settings["servers"]))?.firstOrNull()
        return Json.obj(first)?.get("address") as? String
    }

    private fun containsInsecure(v: Any?): Boolean = when (v) {
        is Map<*, *> -> v.entries.any { (k, x) ->
            (k.toString().equals("allowInsecure", ignoreCase = true) && (x == true || x == "true" || x == 1L)) ||
                containsInsecure(x)
        }
        is Iterable<*> -> v.any { containsInsecure(it) }
        else -> false
    }

    private fun streamSettings(c: VlessConfig): Map<String, Any?> {
        val s = linkedMapOf<String, Any?>("network" to c.network, "security" to c.security)
        when (c.security) {
            "reality" -> s["realitySettings"] = linkedMapOf(
                "serverName" to c.sni,
                "fingerprint" to c.fingerprint,
                "publicKey" to c.publicKey,
                "shortId" to c.shortId,
                "spiderX" to c.spiderX,
            )
            "tls" -> s["tlsSettings"] = linkedMapOf<String, Any?>(
                "serverName" to c.sni,
                "fingerprint" to c.fingerprint,
            ).also { if (c.alpn.isNotEmpty()) it["alpn"] = c.alpn }
        }
        when (c.network) {
            "ws" -> s["wsSettings"] = linkedMapOf<String, Any?>("path" to c.path)
                .also { if (c.hostHeader.isNotEmpty()) it["headers"] = linkedMapOf("Host" to c.hostHeader) }
            "grpc" -> s["grpcSettings"] = linkedMapOf("serviceName" to c.serviceName)
            "httpupgrade" -> s["httpupgradeSettings"] = linkedMapOf<String, Any?>("path" to c.path)
                .also { if (c.hostHeader.isNotEmpty()) it["host"] = c.hostHeader }
            "xhttp" -> s["xhttpSettings"] = linkedMapOf<String, Any?>("path" to c.path, "mode" to "auto")
                .also { if (c.hostHeader.isNotEmpty()) it["host"] = c.hostHeader }
        }
        return s
    }

    private fun build(proxyOutbound: Map<String, Any?>, serverHost: String?, socksPort: Int): String {
        val dnsServers = ArrayList<Any?>()
        val rules = ArrayList<Any?>()
        if (serverHost != null && !VlessUri.isIp(serverHost)) {
            // Bootstrap: the proxy server's own name must be resolved WITHOUT the tunnel it is about to create.
            dnsServers.add(
                linkedMapOf(
                    "address" to "1.1.1.1",
                    "port" to 53L,
                    "domains" to listOf("full:$serverHost"),
                    "skipFallback" to true,
                ),
            )
            rules.add(
                linkedMapOf(
                    "type" to "field",
                    "ip" to listOf("1.1.1.1"),
                    "network" to "udp",
                    "port" to "53",
                    "outboundTag" to "direct",
                ),
            )
        }
        // Everything else is resolved remotely (DoH through the tunnel): no local/ISP DNS involved.
        dnsServers.add("https://1.1.1.1/dns-query")
        dnsServers.add("https://8.8.8.8/dns-query")

        val root = linkedMapOf<String, Any?>(
            "log" to linkedMapOf("loglevel" to "warning"),
            "dns" to linkedMapOf("servers" to dnsServers, "queryStrategy" to "UseIPv4"),
            "inbounds" to listOf(
                linkedMapOf(
                    "tag" to "socks-in",
                    "listen" to "127.0.0.1",
                    "port" to socksPort.toLong(),
                    "protocol" to "socks",
                    "settings" to linkedMapOf("udp" to true, "auth" to "noauth"),
                    "sniffing" to linkedMapOf(
                        "enabled" to true,
                        "destOverride" to listOf("http", "tls", "quic"),
                        "routeOnly" to true,
                    ),
                ),
            ),
            "outbounds" to listOf(
                proxyOutbound,
                linkedMapOf("tag" to "direct", "protocol" to "freedom"),
                linkedMapOf("tag" to "block", "protocol" to "blackhole"),
            ),
            "routing" to linkedMapOf("domainStrategy" to "AsIs", "rules" to rules),
        )
        return Json.stringify(root, pretty = true)
    }
}
