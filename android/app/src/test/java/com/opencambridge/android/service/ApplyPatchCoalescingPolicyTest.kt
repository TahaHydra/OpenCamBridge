package com.opencambridge.android.service

import com.opencambridge.android.server.UpdateSettingsRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyPatchCoalescingPolicyTest {
    private fun patch(
        source: String,
        revision: Long = 10,
        fps: Int? = 30,
        mirror: Boolean? = null
    ) = UpdateSettingsRequest(
        baseRevision = revision,
        requestId = "$source-$revision-${fps ?: mirror}",
        clientType = source,
        fps = fps,
        mirror = mirror
    )

    @Test fun sameClientRevisionAndCategoryMayCoalesce() {
        assertTrue(ApplyPatchCoalescingPolicy.canMerge(patch("phone"), "phone", patch("phone", fps = 60), "phone"))
    }

    @Test fun webPhoneAndTauriNeverCrossMerge() {
        assertFalse(ApplyPatchCoalescingPolicy.canMerge(patch("web"), "web", patch("phone"), "phone"))
        assertFalse(ApplyPatchCoalescingPolicy.canMerge(patch("phone"), "phone", patch("tauri"), "tauri"))
    }

    @Test fun sharedTransportSourceDoesNotMergeDifferentClients() {
        assertFalse(
            ApplyPatchCoalescingPolicy.canMerge(
                patch("web"), "settings-api",
                patch("phone"), "settings-api"
            )
        )
    }

    @Test fun differentBaseRevisionsNeverMerge() {
        assertFalse(ApplyPatchCoalescingPolicy.canMerge(patch("web", 10), "web", patch("web", 11), "web"))
    }

    @Test fun incompatibleCategoriesNeverMerge() {
        assertFalse(
            ApplyPatchCoalescingPolicy.canMerge(
                patch("phone"), "phone",
                patch("phone", fps = null, mirror = true), "phone"
            )
        )
    }
}
