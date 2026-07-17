package com.opencambridge.android.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTransformPolicyTest {
    @Test fun backCameraCombinesSensorDeviceAndManualRotation() {
        val value = FrameTransformPolicy.calculate(90, 90, CameraCharacteristics.LENS_FACING_BACK, "270", true)
        assertEquals(270, value.effectiveRotation)
        assertEquals(90, value.sensorOrientation)
        assertEquals(90, value.deviceRotation)
        assertEquals(true, value.mirror)
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
