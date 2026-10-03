package com.opencambridge.android.pairing

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private data class IdentifyQuery(val service: String, val credentialId: String, val nonce: String)

/** Owned by the bound LAN server; Stop closes the socket and wakes the receive loop. */
class PhoneDiscovery(private val store: PairingStore, private val port: Int) : AutoCloseable {
    private val socket = DatagramSocket(null).apply { bind(InetSocketAddress("0.0.0.0", 47654)) }
    private val worker = Thread({
        var window = System.nanoTime()
        var replies = 0
        while (!socket.isClosed) {
            val packet = DatagramPacket(ByteArray(513), 513)
            try { socket.receive(packet) } catch (_: SocketException) { break } catch (_: Exception) { continue }
            if (packet.length > 512 || !PairingInput.privateIpv4(packet.address.hostAddress.orEmpty())) continue
            val now = System.nanoTime()
            if (now - window >= 1_000_000_000L) { window = now; replies = 0 }
            if (replies >= 10) continue
            try {
                val query = Json.decodeFromString<IdentifyQuery>(String(packet.data, 0, packet.length, Charsets.UTF_8))
                if (query.service != "ocb-phone-v1") continue
                val proof = store.authorization.identify(store.phoneId, port, query.credentialId, query.nonce) ?: continue
                val response = Json.encodeToString(proof).toByteArray()
                socket.send(DatagramPacket(response, response.size, packet.address, packet.port))
                replies++
            } catch (_: Exception) { /* Malformed discovery is deliberately silent. */ }
        }
    }, "ocb-phone-discovery").apply { isDaemon = true; start() }
    override fun close() { socket.close() }
}
