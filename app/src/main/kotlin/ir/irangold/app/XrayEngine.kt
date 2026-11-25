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
        port = NetUtil.freePort()
        val json = try {
            buildConfig(text, port)
        } catch (e: ConfigException) {
            state = EngineState.FAILED
            throw EngineException(e.message ?: "کانفیگ نامعتبر")
        }
        try {
            val p = ProcessBuilder(bin.absolutePath, "run", "-c", "stdin:")
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
                .start()
            proc = p
            p.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) } // config in, then EOF
            pump(p)
            waitForPort(10_000)
            state = EngineState.RUNNING
        } catch (e: Exception) {
            val msg = e.message
            stop()
            state = EngineState.FAILED
            throw if (e is EngineException) e else EngineException(msg ?: "Xray اجرا نشد", e)
        }
    }

    companion object {
        const val CONFIG_KEY = "xray.import"

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
            val t = text.trim()
            buildConfig(t, 10808)
            return if (t.startsWith("vless://", ignoreCase = true)) {
                val c = VlessUri.parse(t)
                "VLESS · ${c.security.uppercase()} · ${c.network}" + if (c.name.isNotEmpty()) " · ${c.name}" else ""
            } else {
                "کانفیگ JSON"
            }
        }
    }
}
