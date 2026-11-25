package ir.irangold.core

/** Scrubs secrets from any text before it can reach a log or the UI. */
object Redactor {
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val SCHEME = Regex("(?i)\\b(vless|vmess|trojan|ss|hysteria2?)://\\S+")
    private val KV = Regex("(?i)(pbk|publicKey|password|privateKey|sid|shortId|id)(\"?\\s*[=:]\\s*\"?)([^\"&\\s,}]+)")

    fun redact(s: String): String =
        s.replace(SCHEME, "$1://***")
            .replace(KV) { m -> m.groupValues[1] + m.groupValues[2] + "***" }
            .replace(UUID, "****-uuid-****")
}
