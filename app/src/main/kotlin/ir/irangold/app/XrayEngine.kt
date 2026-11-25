package ir.irangold.app

import android.content.Context
import ir.irangold.core.Capabilities
import ir.irangold.core.ConfigException
import ir.irangold.core.Dependency
import ir.irangold.core.EngineException
import ir.irangold.core.EngineState
import ir.irangold.core.VlessUri
import ir.irangold.core.XrayConfigBuilder
import java.io.File

/**
 * Xray-core (MPL-2.0), the unmodified official release binary shipped as libxray.so.
 * Needs a server config that the USER imports; the app never ships a server or a key.
 * The final config is piped over stdin, so it never touches the disk.
 */
class XrayEngine(private val ctx: Context, private val secrets: SecretStore) : ProcessEngine() {
    override val id = "xray"
    override val displayName = "Xray (VLESS)"

    private val bin: File get() = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")

    override fun capabilities() = Capabilities(tcp = true, udp = true, dependency = Dependency.NEEDS_USER_CONFIG)

    override fun isAvailable(): Boolean = unavailableReason() == null

    override fun unavailableReason(): String? = when {
        !bin.exists() -> "فایل اجرایی Xray در برنامه نیست"
        secrets.get(CONFIG_KEY) == null -> "هنوز کانفیگی وارد نشده است"
        else -> null
    }

    override fun start() {
        val text = secrets.get(CONFIG_KEY) ?: throw EngineException("کانفیگی وارد نشده است")
        stop()
        state = EngineState.STARTING
        var lastErr = "Xray اجرا نشد"
        val cands = candidates(text).take(6)
        for ((i, cand) in cands.withIndex()) {
            Gold.progress("Xray: آزمایش کانفیگ ${i + 1} از ${cands.size}…")
            try {
                launch(cand)
                // With several configs (subscription) keep the first one that really passes a quick end-to-end check.
                if (cands.size > 1) {
                    val rep = ir.irangold.core.HealthChecker(ir.irangold.core.ProxyEndpoint("127.0.0.1", port), speedTest = false, timeoutMs = 8000).run()
                    if (!rep.ok) throw EngineException("کانفیگ ${i + 1}: ${rep.failedStage}")
                }
                state = EngineState.RUNNING
                return
            } catch (e: Exception) {
                lastErr = e.message ?: lastErr
                val p = proc; proc = null
                p?.destroy()
            }
        }
        stop()
        state = EngineState.FAILED
        throw EngineException(lastErr)
    }

    private fun launch(cand: String) {
        port = NetUtil.freePort()
        val json = try {
            buildConfig(cand, port)
        } catch (e: ConfigException) {
            throw EngineException(e.message ?: "کانفیگ نامعتبر")
        }
        val p = ProcessBuilder(bin.absolutePath, "run", "-c", "stdin:")
            .directory(ctx.filesDir).redirectErrorStream(true).start()
        proc = p
        p.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) } // config in, then EOF
        pump(p)
        waitForPort(10_000)
    }

    companion object {
        const val CONFIG_KEY = "xray.import"

        /** One entry per vless:// line (a subscription), or the whole text for a JSON config. */
        fun candidates(text: String): List<String> {
            val t = text.trim()
            if (t.startsWith("{")) return listOf(t)
            return t.lines().map { it.trim() }.filter { it.startsWith("vless://", ignoreCase = true) }
        }

        fun buildConfig(text: String, socksPort: Int): String {
            val t = text.trim()
            return when {
                t.startsWith("vless://", ignoreCase = true) -> XrayConfigBuilder.fromVless(VlessUri.parse(t), socksPort)
                t.startsWith("{") -> XrayConfigBuilder.fromRawJson(t, socksPort)
                else -> throw ConfigException("فقط لینک vless:// یا فایل JSON پشتیبانی می‌شود")
            }
        }

        /** Validates an import (throws ConfigException) and returns a secret-free one-line summary. */
        fun validate(text: String): String {
            val list = candidates(text)
            if (list.isEmpty()) throw ConfigException("هیچ لینک vless:// یا JSON معتبری پیدا نشد")
            for (c in list) buildConfig(c, 10808) // every entry must be valid and safe
            if (list.size > 1) return "${list.size} کانفیگ VLESS (اشتراک)"
            val one = list[0]
            return if (one.startsWith("vless://", ignoreCase = true)) {
                val c = VlessUri.parse(one)
                "VLESS · ${c.security.uppercase()} · ${c.network}" + if (c.name.isNotEmpty()) " · ${c.name}" else ""
            } else {
                "کانفیگ JSON"
            }
        }
    }
}
