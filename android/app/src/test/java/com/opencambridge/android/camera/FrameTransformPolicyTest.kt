package com.opencambridge.android.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTransformPolicyTest {
    @Test fun backCameraCombinesSensorDeviceAndManualRotation() {
        val value = FrameTransformPolicy.calculate(90, 90, CameraCharacteristics.LENS_FACING_BACK, "270", true)
        assertEquals(270, value.effectiveRotation)
        assertEquals(90, value.sensorOrientation)
        assertEquals(90, value.deviceRotation)
        assertEquals(true, value.mirror)
    }

    @Test fun localPreviewTrailsTheConsumerByTheSurfaceQuarterTurn() {
        // The surface contributes the SENSOR orientation, so the local preview is
        // the consumer rotation less one quarter turn. Verified on device in both
        // phone orientations; deriving it from the manual offset alone was tried
        // and flipped the picture.
        val back = CameraCharacteristics.LENS_FACING_BACK
        for (device in listOf(0, 90, 180, 270)) {
            val t = FrameTransformPolicy.calculate(90, device, back, "0", false)
            assertEquals(
                "device=$device",
                FrameTransformPolicy.localPreviewRotation(t.effectiveRotation),
                t.previewRotation
            )
        }
        assertEquals(0, FrameTransformPolicy.calculate(90, 0, back, "0", false).previewRotation)
        assertEquals(270, FrameTransformPolicy.calculate(90, 90, back, "0", false).previewRotation)
    }

    @Test fun manualOffsetMovesTheLocalPreviewToo() {
        // The rotation buttons must still turn the phone's own preview, not only the
        // picture the desktop receives.
        val back = CameraCharacteristics.LENS_FACING_BACK
        val byOffset = listOf("0", "90", "180", "270").map {
            FrameTransformPolicy.calculate(90, 0, back, it, false).previewRotation
        }
        assertEquals(listOf(0, 90, 180, 270), byOffset)
    }

    @Test fun autoRotationIsTheConsumerRotationWithoutTheOffset() {
        // autoRotation decides the SHAPE of the picture the surface presents, so it
        // must stay separable from the manual offset.
        val back = CameraCharacteristics.LENS_FACING_BACK
        for (device in listOf(0, 90, 180, 270)) {
            for (offset in listOf(0, 90, 180, 270)) {
                val t = FrameTransformPolicy.calculate(90, device, back, offset.toString(), false)
                assertEquals(
                    "effective must be auto + manual",
                    t.effectiveRotation,
                    (t.autoRotation + offset) % 360
                )
            }
        }
        // Phone upright: the sensor offset is the whole correction, so the surface
        // hands over a portrait picture. Turned a quarter turn: they cancel and it
        // is landscape. These two cases are what the preview transform keys off.
        assertEquals(90, FrameTransformPolicy.calculate(90, 0, back, "0", false).autoRotation)
        assertEquals(0, FrameTransformPolicy.calculate(90, 90, back, "0", false).autoRotation)
    }

    @Test fun everyRotationStaysCanonical() {
        for (facing in listOf(CameraCharacteristics.LENS_FACING_BACK, CameraCharacteristics.LENS_FACING_FRONT)) {
            for (device in listOf(0, 90, 180, 270)) {
                for (offset in listOf("0", "90", "180", "270")) {
                    val t = FrameTransformPolicy.calculate(90, device, facing, offset, false)
                    for ((name, value) in listOf(
                        "effective" to t.effectiveRotation,
                        "auto" to t.autoRotation,
                        "preview" to t.previewRotation
                    )) {
                        assertTrue("$name rotation $value is not canonical", value in setOf(0, 90, 180, 270))
                    }
                }
            }
        }
    }

    @Test fun frontCameraUsesOppositeDeviceDirection() {
        assertEquals(
            180,
            FrameTransformPolicy.calculate(90, 90, CameraCharacteristics.LENS_FACING_FRONT, "auto", false).effectiveRotation
        )
    }

    @Test fun rotationIsAlwaysCanonical() {
        assertEquals(
            0,
            FrameTransformPolicy.calculate(270, 270, CameraCharacteristics.LENS_FACING_FRONT, "180", false).effectiveRotation
        )
    }
}
