package ir.irangold.core

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

class Socks5Exception(message: String, val reply: Int = -1) : IOException(message)

/** Minimal SOCKS5 client (no auth, CONNECT by DOMAIN NAME so DNS is resolved by the proxy side). */
object Socks5 {
    private fun readN(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw IOException("connection closed by proxy")
            off += r
        }
        return buf
    }

    private fun open(proxy: ProxyEndpoint, timeoutMs: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(proxy.host, proxy.port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            val r = readN(s.getInputStream(), 2)
            if (r[0].toInt() != 5 || r[1].toInt() != 0) throw Socks5Exception("SOCKS5 handshake rejected")
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) { }
            throw e
        }
    }

    /** Connects to the proxy and performs only the SOCKS5 greeting. */
    fun greet(proxy: ProxyEndpoint, timeoutMs: Int) {
        open(proxy, timeoutMs).close()
    }

    fun connect(proxy: ProxyEndpoint, host: String, port: Int, timeoutMs: Int): Socket {
        val hb = host.toByteArray(Charsets.US_ASCII)
        require(hb.size in 1..255) { "host length" }
        val s = open(proxy, timeoutMs)
        try {
            val req = ByteArray(7 + hb.size)
            req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3
            req[4] = hb.size.toByte()
            System.arraycopy(hb, 0, req, 5, hb.size)
            req[5 + hb.size] = (port shr 8).toByte()
            req[6 + hb.size] = port.toByte()
            val out = s.getOutputStream()
            out.write(req)
            out.flush()
            val inp = s.getInputStream()
            val h = readN(inp, 4)
            if (h[0].toInt() != 5) throw Socks5Exception("bad SOCKS5 reply version")
            if (h[1].toInt() != 0) throw Socks5Exception("SOCKS5 connect failed (code ${h[1].toInt()})", h[1].toInt())
            val skip = when (h[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> readN(inp, 1)[0].toInt() and 0xff
                else -> throw Socks5Exception("bad SOCKS5 address type")
            }
            readN(inp, skip + 2)
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) { }
            throw e
        }
    }
}
