package io.github.romanvht.byedpi.core

import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Local DNS server bound to the TUN interface address.
 *
 * All device DNS is intercepted (system resolver sends queries to the TUN
 * address, which is local-delivered by the kernel, never entering the tunnel)
 * and resolved over DNS-over-HTTPS. This makes resolution immune to ISP
 * plain-DNS hijacking and national DNS poisoning.
 *
 * Our own DoH sockets are excluded from the VPN via [VpnService.protect].
 */
class LocalDnsServer(
    private val vpn: VpnService,
) {
    companion object {
        private const val TAG = "LocalDnsServer"
        private const val DNS_PORT = 53
        private const val DOH_CONNECT_TIMEOUT_MS = 4000
        private const val DOH_SO_TIMEOUT_MS = 5000
        private const val CACHE_TTL_MS = 60_000L
        private const val CACHE_MAX = 384

        // (SNI hostname, bootstrap IP) — socket goes to IP, TLS SNI/Host to hostname
        private val DOH_RESOLVERS = listOf(
            "dns.google" to "8.8.8.8",
            "dns.google" to "8.8.4.4",
            "unfiltered.adguard-dns.com" to "94.140.14.140",
        )
    }

    private class CacheEntry(val response: ByteArray, val expiresAt: Long)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    val isRunning: Boolean
        get() = udpSocket != null && tcpServer != null

    fun start(interfaceIp: String) {
        stop()
        val addr = try {
            InetAddress.getByName(interfaceIp)
        } catch (e: Exception) {
            Log.e(TAG, "Bad interface address: $interfaceIp", e)
            return
        }
        try {
            DatagramSocket(InetSocketAddress(addr, DNS_PORT)).apply {
                reuseAddress = true
                udpSocket = this
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind UDP DNS on $interfaceIp", e)
            return
        }
        try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(addr, DNS_PORT))
                tcpServer = this
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind TCP DNS on $interfaceIp", e)
            udpSocket?.close()
            udpSocket = null
            return
        }
        Log.i(TAG, "DNS server started on $interfaceIp:$DNS_PORT")

        ioScope.launch { udpLoop() }
        ioScope.launch { tcpLoop() }
    }

    fun stop() {
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        udpSocket = null
        tcpServer = null
        ioScope.coroutineContext[Job]?.cancelChildren()
        cache.clear()
        Log.i(TAG, "DNS server stopped")
    }

    private suspend fun udpLoop() {
        val sock = udpSocket ?: return
        val buf = ByteArray(2048)
        while (!sock.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val query = pkt.data.copyOfRange(0, pkt.length)
                val sender = pkt.socketAddress
                ioScope.launch {
                    val response = resolve(query) ?: return@launch
                    try {
                        sock.send(DatagramPacket(response, response.size, sender))
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP send failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (!sock.isClosed) Log.w(TAG, "UDP loop: ${e.message}")
            }
        }
    }

    private suspend fun tcpLoop() {
        val server = tcpServer ?: return
        while (!server.isClosed) {
            try {
                val client = server.accept()
                ioScope.launch { handleTcpClient(client) }
            } catch (e: Exception) {
                if (server.isClosed.not()) Log.w(TAG, "TCP loop: ${e.message}")
            }
        }
    }

    private suspend fun handleTcpClient(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 10_000
                val input = c.getInputStream()
                val lenBuf = ByteArray(2)
                if (readFully(input, lenBuf) != lenBuf.size) return
                val len = ((lenBuf[0].toInt() and 0xff) shl 8) or (lenBuf[1].toInt() and 0xff)
                if (len <= 0 || len > 65535) return
                val query = ByteArray(len)
                if (readFully(input, query) != len) return
                val response = resolve(query) ?: return
                val out = c.getOutputStream()
                out.write(byteArrayOf(((response.size shr 8) and 0xff).toByte(), (response.size and 0xff).toByte()))
                out.write(response)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "TCP client failed: ${e.message}")
            }
        }
    }

    private suspend fun resolve(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val key = cacheKey(query)
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now < it.expiresAt) return it.response }

        for ((host, ip) in DOH_RESOLVERS) {
            val response = try {
                dohQuery(host, ip, query)
            } catch (e: Exception) {
                Log.w(TAG, "DoH $host ($ip) failed: ${e.message}")
                null
            }
            if (response != null && response.size >= 12) {
                cache[key] = CacheEntry(response, now + CACHE_TTL_MS)
                if (cache.size > CACHE_MAX) {
                    cache.entries.sortedBy { it.value.expiresAt }
                        .take(cache.size - CACHE_MAX)
                        .forEach { cache.remove(it.key) }
                }
                return response
            }
        }
        Log.e(TAG, "All DoH resolvers failed for query")
        return null
    }

    /**
     * Minimal blocking DoH client: POST application/dns-message over a raw
     * SSLSocket with explicit SNI, so no system DNS lookup is needed
     * (socket dials the bootstrap IP directly).
     */
    private fun dohQuery(host: String, ip: String, query: ByteArray): ByteArray? {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket() as SSLSocket
        try {
            vpn.protect(socket) // keep our own traffic out of the VPN tunnel
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(ip, 443), DOH_CONNECT_TIMEOUT_MS)
            socket.soTimeout = DOH_SO_TIMEOUT_MS
            val params = SSLParameters()
            params.serverNames = listOf<SNIServerName>(SNIHostName(host))
            params.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = params
            socket.startHandshake()

            val out = BufferedOutputStream(socket.getOutputStream())
            val head = "POST /dns-query HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Accept: application/dns-message\r\n" +
                "Connection: close\r\n" +
                "Content-Length: ${query.size}\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(query)
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            var statusOk = false
            var contentLength = -1
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("http/")) statusOk = lower.contains(" 200 ")
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substringAfter(':').trim().toIntOrNull() ?: -1
                }
            }
            if (!statusOk) return null

            return if (contentLength >= 0) {
                val body = ByteArray(contentLength)
                if (readFully(input, body) != contentLength) null else body
            } else {
                val baos = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val r = input.read(chunk)
                    if (r < 0) break
                    baos.write(chunk, 0, r)
                }
                baos.toByteArray()
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                var s = sb.toString()
                if (s.endsWith("\r")) s = s.substring(0, s.length - 1)
                return s
            }
            sb.append(b.toChar())
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val r = input.read(buf, read, buf.size - read)
            if (r < 0) return read
            read += r
        }
        return read
    }

    /** Cache key: qname + qtype parsed out of the wire-format query. */
    private fun cacheKey(query: ByteArray): String {
        if (query.size < 12) return "invalid"
        var pos = 12
        val sb = StringBuilder()
        try {
            parse@ while (pos < query.size) {
                val len = query[pos].toInt() and 0xff
                if (len == 0) { pos++; break@parse }
                if (len and 0xc0 != 0) { pos += 2; break@parse }
                pos++
                if (pos + len > query.size) return "invalid"
                for (i in 0 until len) {
                    sb.append(((query[pos].toInt() and 0xff)).toChar())
                    pos++
                }
                sb.append('.')
            }
        } catch (_: Exception) {
            return "invalid"
        }
        val qtype = if (pos + 1 < query.size) {
            ((query[pos].toInt() and 0xff) shl 8) or (query[pos + 1].toInt() and 0xff)
        } else 0
        return "$sb|$qtype"
    }
}
