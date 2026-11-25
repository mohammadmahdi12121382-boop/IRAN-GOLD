package ir.irangold.core

/**
 * Tiny dependency-free JSON reader/writer.
 * Keeps :core a pure-JVM module so it can be tested without the Android SDK.
 * Objects are LinkedHashMap<String, Any?>, arrays are ArrayList<Any?>,
 * numbers are Long or Double.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Reader(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (p.pos != text.length) throw IllegalArgumentException("JSON: trailing data at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    fun stringify(v: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, v, pretty, 0)
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, depth: Int) {
        repeat(depth) { sb.append("  ") }
    }

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Double -> {
                if (v.isNaN() || v.isInfinite()) throw IllegalArgumentException("JSON: NaN/Infinity not allowed")
                if (v == Math.floor(v) && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v.toString())
            }
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) {
                        sb.append('\n')
                        indent(sb, depth + 1)
                    }
                    quote(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, value, pretty, depth + 1)
                }
                if (pretty) {
                    sb.append('\n')
                    indent(sb, depth)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                var first = true
                for (item in items) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) {
                        sb.append('\n')
                        indent(sb, depth + 1)
                    }
                    write(sb, item, pretty, depth + 1)
                }
                if (pretty) {
                    sb.append('\n')
                    indent(sb, depth)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("JSON: unsupported type ${v.javaClass.name}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Reader(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg at $pos")

        fun readValue(): Any? {
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("expected string key")
                val key = readString()
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                out[key] = readValue()
                skipWs()
                if (pos >= s.length) fail("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                out.add(readValue())
                skipWs()
                if (pos >= s.length) fail("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '-' || s[pos] == '+' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) pos++
            val t = s.substring(start, pos)
            val isFloat = t.contains('.') || t.contains('e') || t.contains('E')
            if (!isFloat) {
                t.toLongOrNull()?.let { return it }
            }
            return t.toDoubleOrNull() ?: fail("bad number '$t'")
        }
    }
}
