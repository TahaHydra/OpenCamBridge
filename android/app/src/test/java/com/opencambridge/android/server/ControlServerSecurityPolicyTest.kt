package com.opencambridge.android.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlServerSecurityPolicyTest {
    @Test fun obsUsbNeverReflectsUntrustedQueryTokens() {
        val snapshot = BoundAuthenticationSnapshot("usbOnly", "permanent-secret")
        assertEquals("\"\"", snapshot.obsScriptToken("\";alert(1);//</script><script>"))
    }

    @Test fun obsLanEncodesRequestTokenWithoutLeakingLegacyCredential() {
        val snapshot = BoundAuthenticationSnapshot("lanToken", "permanent-secret")
        assertEquals("\"paired-token\"", snapshot.obsScriptToken("paired-token"))
        assertEquals("\"\"", snapshot.obsScriptToken(null))
        val hostile = "\";alert(1);//</script><script>\u2028"
        val encoded = snapshot.obsScriptToken(hostile)
        assertFalse(encoded.contains('<'))
        assertFalse(encoded.contains('\u2028'))
        assertEquals(hostile, kotlinx.serialization.json.Json.decodeFromString<String>(encoded))
    }
    @Test fun lanRequiresBoundTokenExceptHealth() {
        val snapshot = BoundAuthenticationSnapshot("lanToken", "correct-token")
        assertTrue(snapshot.authorizes("/health", null))
        assertFalse(snapshot.authorizes("/api/camera/status", null))
        assertFalse(snapshot.authorizes("/api/camera/status", "wrong-token"))
        assertTrue(snapshot.authorizes("/api/camera/status", "correct-token"))
    }

    @Test fun usbOnlyDoesNotRequireToken() {
        assertTrue(BoundAuthenticationSnapshot("usbOnly", null).authorizes("/api/camera/status", null))
    }

    @Test fun tokenIsAnImmutableBindTimeSnapshot() {
        var mutableToken = "first"
        val snapshot = BoundAuthenticationSnapshot("lanToken", mutableToken)
        mutableToken = "second"
        assertTrue(snapshot.authorizes("/api/settings", "first"))
        assertFalse(snapshot.authorizes("/api/settings", mutableToken))
    }

    @Test fun emptyExpectedTokenNeverAuthorizesLan() {
        val snapshot = BoundAuthenticationSnapshot("lanToken", "")
        assertFalse(snapshot.authorizes("/api/settings", ""))
        assertFalse(snapshot.authorizes("/api/settings", null))
    }

    @Test fun selectedComparisonPathAcceptsOnlyEqualBytes() {
        val expected = "token".toByteArray()
        assertTrue(ControlServerSecurityPolicy.constantTimeTokenMatches(expected, "token"))
        assertFalse(ControlServerSecurityPolicy.constantTimeTokenMatches(expected, "token-x"))
        assertFalse(ControlServerSecurityPolicy.constantTimeTokenMatches(expected, null))
    }

    @Test fun securityMutationsAreLoopbackOnly() {
        listOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost").forEach {
            assertFalse(ControlServerSecurityPolicy.shouldStripSecurityMutation(it, true))
        }
        assertTrue(ControlServerSecurityPolicy.shouldStripSecurityMutation("192.168.1.10", true))
        assertFalse(ControlServerSecurityPolicy.shouldStripSecurityMutation("192.168.1.10", false))
    }

    @Test fun corsAllowlistIsExact() {
        assertTrue(ControlServerSecurityPolicy.isCorsOriginAllowed("https://tauri.localhost"))
        assertTrue(ControlServerSecurityPolicy.isCorsOriginAllowed("http://localhost:1420"))
        assertTrue(ControlServerSecurityPolicy.isCorsOriginAllowed("https://127.0.0.1:1420"))
        assertFalse(ControlServerSecurityPolicy.isCorsOriginAllowed("https://evil.example"))
        assertFalse(ControlServerSecurityPolicy.isCorsOriginAllowed("http://localhost:1421"))
        assertFalse(ControlServerSecurityPolicy.isCorsOriginAllowed("https://tauri.localhost.evil.example"))
    }
}
