package ir.irangold.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class HttpResult(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/**
 * One HTTPS GET over an already-connected raw socket (e.g. a SOCKS5 tunnel).
 * Certificates AND host names are always verified; there is no insecure mode.
 */
object HttpProbe {
    fun get(
        raw: java.net.Socket,
        host: String,
        port: Int,
        path: String,
        ssl: SSLSocketFactory,
        timeoutMs: Int,
        maxBody: Int = 1 shl 20,
    ): HttpResult {
        val tls = ssl.createSocket(raw, host, port, true) as SSLSocket
        try {
            tls.soTimeout = timeoutMs
            val params = tls.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            tls.sslParameters = params
            tls.startHandshake()
            if (!hostMatches(host, tls.session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate)) {
                throw SSLPeerUnverifiedException("host name mismatch for $host")
            }
            val req = "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nAccept: */*\r\n" +
                "Accept-Encoding: identity\r\nConnection: close\r\n\r\n"
            tls.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            tls.outputStream.flush()
            return readResponse(tls.inputStream, maxBody)
        } finally {
            try { tls.close() } catch (_: Exception) { }
        }
    }

    /** Independent of the platform verifier: the certificate must carry a DNS SAN matching [host] (one-label wildcards allowed). */
    internal fun hostMatches(host: String, cert: java.security.cert.X509Certificate?): Boolean {
        if (cert == null) return false
        val h = host.lowercase()
        val names = cert.subjectAlternativeNames ?: return false
        for (n in names) {
            if ((n[0] as? Int) != 2) continue
            val pat = (n[1] as? String)?.lowercase() ?: continue
            if (pat == h) return true
            if (pat.startsWith("*.") && h.substringAfter('.', "") == pat.substring(2) && h.contains('.')) return true
        }
        return false
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16 * 1024) throw IOException("header line too long")
        }
        return sb.toString()
    }

    private fun readResponse(inp: InputStream, maxBody: Int): HttpResult {
        val statusLine = readLine(inp) ?: throw IOException("empty response")
        val parts = statusLine.split(' ', limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(inp) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val out = ByteArrayOutputStream()
        val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        val length = headers["content-length"]?.toLongOrNull()
        if (status == 204 || status == 304 || status in 100..199) {
            // no body
        } else if (chunked) {
            while (out.size() < maxBody) {
                val sizeLine = readLine(inp) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("bad chunk")
                if (size == 0) break
                copy(inp, out, size.toLong(), maxBody)
                readLine(inp) // CRLF after the chunk
            }
        } else if (length != null) {
            copy(inp, out, length, maxBody)
        } else {
            copy(inp, out, Long.MAX_VALUE, maxBody)
        }
        return HttpResult(status, headers, out.toByteArray())
    }

    private fun copy(inp: InputStream, out: ByteArrayOutputStream, count: Long, maxBody: Int) {
        val buf = ByteArray(8192)
        var left = count
        while (left > 0 && out.size() < maxBody) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) return
            out.write(buf, 0, r)
            left -= r
        }
    }
}
