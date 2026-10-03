package com.opencambridge.android.pairing

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.srp.SRP6Server
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.junit.Assert.*
import org.junit.Test

class SrpInteropTest {
    private val salt = ByteArray(16) { it.toByte() }
    private val identity = "0123456789abcdef0123456789abcdef"
    private val password = "12345678"
    private fun server(): SRP6Server {
        val group = SRP6StandardGroups.rfc5054_2048
        val verifier = SRP6VerifierGenerator().apply { init(group, SHA256Digest()) }
            .generateVerifier(salt, identity.toByteArray(), password.toByteArray())
        return object : SRP6Server() {
            override fun selectPrivateValue(): BigInteger = BigInteger(1, ByteArray(32) { (it + 65).toByte() })
        }.apply { init(group, verifier, SHA256Digest(), SecureRandom()) }
    }
    private fun random() = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) { bytes.indices.forEach { bytes[it] = (it + 1).toByte() } }
    }
    @Test fun mutualProofsAndSessionKeyMatchBouncyCastleServer() {
        val client = PairingSrp(identity, password, random())
        val server = server()
        val b = server.generateServerCredentials()
        server.calculateSecret(BigInteger(client.a, 16))
        val m1 = client.proof(salt.hex(), b.toString(16).padStart(512, '0'))
        assertTrue(server.verifyClientEvidenceMessage(BigInteger(m1, 16)))
        val m2 = server.calculateServerEvidenceMessage().toString(16).padStart(64, '0')
        val key = client.verify(m2)
        assertEquals(server.calculateSessionKey().toString(16).padStart(64, '0'), key.hex())
        println("INTEROP salt=${salt.hex()} id=$identity password=$password A=${client.a} B=${b.toString(16).padStart(512, '0')} M1=$m1 M2=$m2 K=${key.hex()}")
    }
    @Test fun wrongCodeCannotAuthenticateServer() {
        val client = PairingSrp(identity, "87654321", random())
        val server = server()
        val b = server.generateServerCredentials()
        server.calculateSecret(BigInteger(client.a, 16))
        assertFalse(server.verifyClientEvidenceMessage(BigInteger(client.proof(salt.hex(), b.toString(16).padStart(512, '0')), 16)))
        assertThrows(Exception::class.java) { client.verify("00".repeat(32)) }
    }
    @Test fun invalidServerPublicAndNoncanonicalEncodingAreRejected() {
        listOf("00".repeat(256), "02", "FF".repeat(256)).forEach { b ->
            assertThrows(Exception::class.java) { PairingSrp(identity, password).proof(salt.hex(), b) }
        }
    }
}
