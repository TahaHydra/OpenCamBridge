package com.opencambridge.android.pairing

import org.junit.Assert.*
import org.junit.Test

class CompanionCompatibilityTest {
    @Test fun compatibilityRoutePassesTheTransportGuardButUnrelatedPathsDoNot() {
        for (path in listOf("/pair/start", "/pair/prove", "/pair/finish", "/pair/compatibility")) {
            PairingHttp.requirePairingRoute(path)
        }
        for (path in listOf("/api/device/info", "/pair/compatibility?redirect=1", "/pair/compatibility/", "https://example.com")) {
            assertThrows(IllegalArgumentException::class.java) { PairingHttp.requirePairingRoute(path) }
        }
    }
    @Test fun supportedProtocolsAllowDifferentCompatiblePatchVersions() {
        assertNull(CompanionCompatibility("0.2.0", 1, 2, "0.1.0").warning("0.1.9"))
    }
    @Test fun unsupportedProtocolAndTooOldPhoneAreExplained() {
        assertNotNull(CompanionCompatibility("0.1.0", 2, 2, "0.1.0").warning("0.1.0"))
        assertNotNull(CompanionCompatibility("0.2.0", 1, 2, "0.10.0").warning("0.9.9"))
        assertNotNull(CompanionCompatibility("0.1.0", 1, 2, "future").warning("0.1.0"))
    }
}
