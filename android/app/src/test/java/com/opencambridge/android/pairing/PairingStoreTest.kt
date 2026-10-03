package com.opencambridge.android.pairing

import org.junit.Assert.*
import org.junit.Test

class PairingStoreTest {
    private class Memory : PairingPersistence {
        var data: String? = null
        var fail = false
        override fun load() = data
        override fun save(value: String) { if (fail) error("storage unavailable"); data = value }
    }
    @Test fun identityAndPairingsSurviveReloadAndRevokePersists() {
        val disk = Memory()
        val store = PairingStore(disk)
        val pc = store.issue("a".repeat(32), "Work PC")
        val restored = PairingStore(disk)
        assertEquals(store.phoneId, restored.phoneId)
        assertEquals(pc, restored.pcs.value.single())
        restored.revoke(pc.pcId)
        assertTrue(PairingStore(disk).pcs.value.isEmpty())
        assertEquals(store.phoneId, PairingStore(disk).phoneId)
    }
    @Test fun failedSaveCannotAuthorizeUnpersistedToken() {
        val disk = Memory()
        val store = PairingStore(disk)
        disk.fail = true
        assertThrows(IllegalStateException::class.java) { store.issue("a".repeat(32), "PC") }
        assertTrue(store.pcs.value.isEmpty())
    }
}
