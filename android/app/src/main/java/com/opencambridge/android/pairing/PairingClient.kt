package com.opencambridge.android.pairing

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private data class StartRequest(val id: String, val kind: String, val a: String)
@Serializable private data class StartResponse(val session: String, val salt: String, val b: String, val name: String)
@Serializable private data class ProofRequest(val session: String, val m1: String)
@Serializable private data class ProofResponse(val m2: String, val nonce: String, val ciphertext: String)
@Serializable private data class AuthenticatedPc(val pcId: String, val name: String)
@Serializable private data class PhoneCredential(val phoneId: String, val name: String, val port: Int, val token: String, val credentialId: String)
@Serializable private data class FinishRequest(val session: String, val nonce: String, val ciphertext: String)
@Serializable private data class PairingAck(val ok: Boolean, val pcId: String)

/** Short-lived authenticated session. Tokens are created only by the explicit approve action. */
class PendingPairing internal constructor(
    private val endpoint: PairingEndpoint,
    private val session: String,
    private val key: ByteArray,
    val pcId: String,
    val name: String,
    private val deadlineNanos: Long
) : AutoCloseable {
    private var finishRequest: String? = null
    private var closed = false
    suspend fun approve(store: PairingStore, phoneName: String, port: Int) {
        check(!closed && System.nanoTime() < deadlineNanos) { "Pairing expired. Open a new invitation on your PC." }
        require(port in 1024..65535)
        PairingInput.name(phoneName)
        currentCoroutineContext().ensureActive()
        if (finishRequest == null) {
            val credential = withContext(Dispatchers.IO) { store.issue(pcId, name) }
            val payload = PhoneCredential(store.phoneId, phoneName, port, credential.token, credential.credentialId)
            val sealed = PairingCipher.seal(key, session, "phone", Json.encodeToString(payload))
            finishRequest = Json.encodeToString(FinishRequest(session, sealed.nonce, sealed.ciphertext))
        }
        val ack = Json.decodeFromString<SealedPairing>(PairingClient.post(endpoint, "/pair/finish", finishRequest!!))
        val verified = Json.decodeFromString<PairingAck>(PairingCipher.open(key, session, "ack", ack))
        check(verified.ok && verified.pcId == pcId) { "Invalid PC acknowledgement" }
        close()
    }
    override fun close() { closed = true; key.fill(0) }
}

object PairingClient {
    suspend fun discover(manualIp: String = ""): List<PairingEndpoint> = withContext(Dispatchers.IO) {
        require(manualIp.isEmpty() || PairingInput.privateIpv4(manualIp)) { "Enter a private IPv4 address, such as 192.168.1.20" }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val targets = if (manualIp.isNotEmpty()) listOf(manualIp) else {
                val broadcasts = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast?.hostAddress }
                (broadcasts + "255.255.255.255").distinct().take(8)
            }
            val query = "OCB_PAIR_DISCOVER_V1".toByteArray()
            targets.forEach { host -> socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), 47653)) }
            val deadline = System.nanoTime() + 1_800_000_000L
            val found = linkedMapOf<String, PairingEndpoint>()
            var packets = 0
            while (System.nanoTime() < deadline && found.size < 16 && packets < 64) {
                currentCoroutineContext().ensureActive()
                val packet = DatagramPacket(ByteArray(1025), 1025)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                packets++
                val host = packet.address.hostAddress ?: continue
                if (manualIp.isNotEmpty() && host != manualIp || packet.length > 1024) continue
                val pc = runCatching { PairingInput.announcement(String(packet.data, 0, packet.length, Charsets.UTF_8), host) }.getOrNull() ?: continue
                found["${pc.id}:${pc.host}:${pc.port}"] = pc
            }
            found.values.toList()
        }
    }
    suspend fun authenticate(endpoint: PairingEndpoint, password: String, kind: String): PendingPairing {
        require(kind == "code" || kind == "qr")
        require(PairingInput.privateIpv4(endpoint.host) && endpoint.port in 1024..65535)
        val deadline = System.nanoTime() + 115_000_000_000L
        val srp = PairingSrp(endpoint.id, password)
        val start = Json.decodeFromString<StartResponse>(post(endpoint, "/pair/start", Json.encodeToString(StartRequest(endpoint.id, kind, srp.a))))
        hexBytes(start.session, 16)
        val proof = srp.proof(start.salt, start.b)
        val response = Json.decodeFromString<ProofResponse>(post(endpoint, "/pair/prove", Json.encodeToString(ProofRequest(start.session, proof))))
        val key = srp.verify(response.m2)
        try {
            val pc = Json.decodeFromString<AuthenticatedPc>(PairingCipher.open(key, start.session, "pc", SealedPairing(response.nonce, response.ciphertext)))
            hexBytes(pc.pcId, 16); PairingInput.name(pc.name)
            return PendingPairing(endpoint, start.session, key, pc.pcId, pc.name, deadline)
        } catch (e: Exception) { key.fill(0); throw e }
    }
    internal suspend fun post(endpoint: PairingEndpoint, path: String, payload: String): String = withContext(Dispatchers.IO) {
        val response = PairingHttp.post(endpoint, path, payload)
        currentCoroutineContext().ensureActive()
        response
    }
}
