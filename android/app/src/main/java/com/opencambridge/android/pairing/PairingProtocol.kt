package com.opencambridge.android.pairing

import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.agreement.srp.SRP6Util
import org.bouncycastle.crypto.digests.SHA256Digest

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun hexBytes(value: String, size: Int): ByteArray {
    require(value.length == size * 2 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid pairing data" }
    return ByteArray(size) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
internal fun randomHex(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }.hex()

@Serializable data class PairingInvitation(val v: Int, val service: String, val id: String, val hosts: List<String>, val port: Int, val secret: String)
@Serializable data class PcAnnouncement(val service: String, val id: String, val name: String, val port: Int)
data class PairingEndpoint(val host: String, val port: Int, val id: String, val name: String)

object PairingInput {
    fun privateIpv4(value: String): Boolean {
        val pieces = value.split('.')
        if (pieces.size != 4 || pieces.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it[0] == '0') || it.any { c -> c !in '0'..'9' } }) return false
        val n = pieces.map { it.toInt() }
        return n.all { it in 0..255 } && (n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168))
    }
    fun name(value: String): String {
        require(value.isNotBlank() && value.length <= 80 && value.none { it.isISOControl() }) { "Invalid device name" }
        return value
    }
    fun code(value: String): String {
        val normalized = value.replace(" ", "")
        require(normalized.length == 8 && normalized.all { it in '0'..'9' }) { "Enter the eight-digit code shown on your PC" }
        return normalized
    }
    fun qr(value: String): PairingInvitation {
        require(value.length <= 4096) { "QR code is too large" }
        val qr = try { Json.decodeFromString<PairingInvitation>(value) } catch (_: Exception) { throw IllegalArgumentException("Not an OpenCamBridge pairing QR code") }
        require(qr.v == 1 && qr.service == "ocb-pair-v1" && qr.port in 1024..65535 && qr.hosts.size in 1..8 && qr.hosts.all(::privateIpv4)) { "QR code must contain private LAN addresses" }
        hexBytes(qr.id, 16); hexBytes(qr.secret, 32)
        return qr
    }
    fun announcement(value: String, host: String): PairingEndpoint {
        require(value.length <= 1024 && privateIpv4(host))
        val pc = Json.decodeFromString<PcAnnouncement>(value)
        require(pc.service == "ocb-pair-v1" && pc.port in 1024..65535)
        hexBytes(pc.id, 16)
        return PairingEndpoint(host, pc.port, pc.id, name(pc.name))
    }
}

/** Bouncy Castle owns all SRP arithmetic and mutual evidence checks. */
class PairingSrp(identity: String, password: String, random: SecureRandom = SecureRandom()) {
    private val srp = object : SRP6Client() {
        fun setSalt(salt: ByteArray, identity: ByteArray, password: ByteArray) {
            x = SRP6Util.calculateX(digest, N, salt, identity, password)
        }
        // The wire contract uses a random 256-bit exponent, not BC's default group-sized value.
        override fun selectPrivateValue(): BigInteger {
            var value: BigInteger
            do { value = BigInteger(1, ByteArray(32).also { random.nextBytes(it) }) } while (value.signum() == 0)
            return value
        }
    }.apply { init(SRP6StandardGroups.rfc5054_2048, SHA256Digest(), random) }
    private val identityBytes = identity.toByteArray(Charsets.UTF_8)
    private val passwordBytes = password.toByteArray(Charsets.UTF_8)
    // A is independent of salt; BC needs the real salt when deriving x, so generate once on receipt.
    private var started = false
    private var publicA: BigInteger? = null
    init {
        hexBytes(identity, 16)
        require(password.length == 8 && password.all { it in '0'..'9' } || password.length == 64 && password.all { it in '0'..'9' || it in 'a'..'f' })
        publicA = srp.generateClientCredentials(ByteArray(16), identityBytes, passwordBytes)
    }
    val a: String get() = publicA!!.toString(16).padStart(512, '0')
    fun proof(salt: String, b: String): String {
        check(!started); started = true
        val saltBytes = hexBytes(salt, 16)
        val server = BigInteger(1, hexBytes(b, 256))
        require(server.signum() > 0 && server < SRP6StandardGroups.rfc5054_2048.n)
        // Recalculate x using the received salt without replacing our already-sent ephemeral.
        srp.setSalt(saltBytes, identityBytes, passwordBytes)
        srp.calculateSecret(server)
        passwordBytes.fill(0)
        return srp.calculateClientEvidenceMessage().toString(16).padStart(64, '0')
    }
    fun verify(m2: String): ByteArray {
        check(srp.verifyServerEvidenceMessage(BigInteger(1, hexBytes(m2, 32)))) { "PC authentication failed" }
        return hexBytes(srp.calculateSessionKey().toString(16).padStart(64, '0'), 32)
    }
}

@Serializable data class SealedPairing(val nonce: String, val ciphertext: String)
object PairingCipher {
    fun seal(key: ByteArray, session: String, direction: String, plaintext: String): SealedPairing {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("ocb-pair-v1:$session:$direction".toByteArray())
        return SealedPairing(nonce.hex(), cipher.doFinal(plaintext.toByteArray()).hex())
    }
    fun open(key: ByteArray, session: String, direction: String, sealed: SealedPairing): String {
        require(sealed.ciphertext.length in 32..8192 && sealed.ciphertext.length % 2 == 0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, hexBytes(sealed.nonce, 12)))
        cipher.updateAAD("ocb-pair-v1:$session:$direction".toByteArray())
        return cipher.doFinal(hexBytes(sealed.ciphertext, sealed.ciphertext.length / 2)).toString(Charsets.UTF_8)
    }
}
