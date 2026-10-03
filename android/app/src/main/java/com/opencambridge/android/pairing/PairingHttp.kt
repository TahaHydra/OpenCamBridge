package com.opencambridge.android.pairing

import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** The only socket HTTP client: fixed pairing routes, literal RFC1918 peers, SRP/AES payloads.
 * This intentionally does not change Android's app-wide cleartext traffic policy. */
internal object PairingHttp {
    fun post(endpoint: PairingEndpoint, path: String, payload: String): String {
        require(PairingInput.privateIpv4(endpoint.host) && endpoint.port in 1024..65535)
        require(path in setOf("/pair/start", "/pair/prove", "/pair/finish"))
        val body = payload.toByteArray(Charsets.UTF_8)
        require(body.size <= 4096)
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 3000)
            val deadline = System.nanoTime() + 4_000_000_000L
            val header = "POST $path HTTP/1.1\r\nHost: ${endpoint.host}:${endpoint.port}\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
            readResponse(socket.getInputStream()) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) throw SocketTimeoutException("Pairing timed out")
                socket.soTimeout = remaining.toInt().coerceAtLeast(1)
            }
        }
    }
    internal fun readResponse(input: InputStream, beforeRead: () -> Unit): String {
        var headerBytes = 0
        fun line(): String {
            val text = StringBuilder()
            while (true) {
                require(++headerBytes <= 4096) { "Pairing headers too large" }
                beforeRead()
                val byte = input.read()
                if (byte < 0) throw EOFException()
                if (byte == 10) { require(text.endsWith("\r")); return text.dropLast(1).toString() }
                require(byte in 32..126 || byte == 13)
                text.append(byte.toChar())
            }
        }
        val status = line().split(' ')
        check(status.size >= 2 && status[0] in setOf("HTTP/1.0", "HTTP/1.1") && status[1] == "200") {
            "Pairing was rejected or expired. Check the code and open a new PC invitation."
        }
        var length: Int? = null
        while (true) {
            val line = line()
            if (line.isEmpty()) break
            val parts = line.split(':', limit = 2)
            require(parts.size == 2)
            when (parts[0].lowercase()) {
                "content-length" -> { require(length == null); length = parts[1].trim().toInt(); require(length in 1..8192) }
                "transfer-encoding" -> error("Unsupported pairing transfer encoding")
            }
        }
        val bytes = ByteArray(requireNotNull(length))
        var offset = 0
        while (offset < bytes.size) {
            beforeRead()
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count <= 0) throw EOFException()
            offset += count
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
