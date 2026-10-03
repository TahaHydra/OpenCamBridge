package com.opencambridge.android.pairing

import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    @Test fun onlyCanonicalPrivateLiteralsAreAllowed() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.2").forEach { assertTrue(PairingInput.privateIpv4(it)) }
        listOf("127.0.0.1", "172.32.0.1", "8.8.8.8", "localhost", "10.0.0.01", "10.0.0.256", "10.0.0.1/24", "::1", "10.0.0.1:80").forEach { assertFalse(PairingInput.privateIpv4(it)) }
    }
    @Test fun qrRejectsUntrustedEndpointsAndMalformedSecrets() {
        val good = """{"v":1,"service":"ocb-pair-v1","id":"${"a".repeat(32)}","hosts":["192.168.1.2"],"port":47655,"secret":"${"b".repeat(64)}"}"""
        assertEquals(47655, PairingInput.qr(good).port)
        listOf(good.replace("192.168.1.2", "example.org"), good.replace("47655", "0"), good.replace("b".repeat(64), "12345678"), good.replace("\"v\":1", "\"v\":2")).forEach { assertThrows(IllegalArgumentException::class.java) { PairingInput.qr(it) } }
        assertEquals("12345678", PairingInput.code("1234 5678"))
        assertThrows(IllegalArgumentException::class.java) { PairingInput.code("1234567x") }
    }
    @Test fun revokeCancelsExistingJobsAndRejectsLateRegistration() {
        val registry = PairedAuthorization()
        registry.replace(listOf(PairedPc("a".repeat(32), "PC", "b".repeat(32), "c".repeat(64))))
        val existing = Job()
        assertTrue(registry.authorize("c".repeat(64), existing))
        registry.replace(emptyList())
        assertTrue(existing.isCancelled)
        val late = Job()
        assertFalse(registry.authorize("c".repeat(64), late))
    }
    @Test fun rotationCancelsOnlyReplacedCredential() {
        val registry = PairedAuthorization()
        val first = PairedPc("a".repeat(32), "One", "b".repeat(32), "c".repeat(64))
        val second = PairedPc("d".repeat(32), "Two", "e".repeat(32), "f".repeat(64))
        registry.replace(listOf(first, second))
        val old = Job(); val other = Job()
        assertTrue(registry.authorize(first.token, old)); assertTrue(registry.authorize(second.token, other))
        registry.replace(listOf(first.copy(token = "0".repeat(64), credentialId = "1".repeat(32)), second))
        assertTrue(old.isCancelled); assertTrue(other.isActive)
        assertFalse(registry.authorize(first.token, Job()))
    }
    @Test fun gcmAuthenticatesCiphertextAndDirection() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = PairingCipher.seal(key, "session", "pc", "hello")
        assertEquals("hello", PairingCipher.open(key, "session", "pc", sealed))
        assertThrows(Exception::class.java) { PairingCipher.open(key, "session", "phone", sealed) }
        assertThrows(Exception::class.java) { PairingCipher.open(key, "session", "pc", sealed.copy(ciphertext = "00" + sealed.ciphertext.drop(2))) }
    }
}
