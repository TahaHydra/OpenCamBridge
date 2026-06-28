package com.opencambridge.android.camera

import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import com.opencambridge.android.state.StreamState
import kotlin.math.abs

object ResolutionPolicy {

    private fun landscapeW(size: Size) = Math.max(size.width, size.height)
    private fun landscapeH(size: Size) = Math.min(size.width, size.height)

    private fun isNearAspect(size: Size, w: Int, h: Int, tolerance: Double = 0.025): Boolean {
        val ratio = landscapeW(size).toDouble() / landscapeH(size).toDouble()
        val target = w.toDouble() / h.toDouble()
        return kotlin.math.abs(ratio - target) <= tolerance
    }

    fun buildSelector(
        profile: String,
        requestedWidth: Int,
        requestedHeight: Int,
        allowNative: Boolean,
        allowAspectFallback: Boolean
    ): ResolutionSelector {
        val filter = ResolutionFilter { supportedSizes, _ ->
            val validSizes = mutableListOf<Size>()
            var fallbackUsed = false
            var resolutionPolicyName = ""
            
            when (profile) {
                "low-latency" -> {
                    resolutionPolicyName = "strict_low_latency"
                    validSizes.addAll(supportedSizes.filter { isNearAspect(it, 16, 9) && landscapeW(it) <= 1280 })
                    validSizes.sortBy { size ->
                        val lw = landscapeW(size)
                        val lh = landscapeH(size)
                        when {
                            lw == 960 && lh == 540 -> 0
                            lw == 1280 && lh == 720 -> 1
                            lw == 640 && lh == 360 -> 2
                            else -> 100 + Math.abs(lw - 960)
                        }
                    }
                }
                "balanced", "balanced-720p60" -> {
                    resolutionPolicyName = if (profile == "balanced-720p60") {
                        "strict_balanced_720p60"
                    } else {
                        "strict_balanced"
                    }
                    validSizes.addAll(supportedSizes.filter { isNearAspect(it, 16, 9) && landscapeW(it) <= 1920 })
                    validSizes.sortBy { size ->
                        val lw = landscapeW(size)
                        val lh = landscapeH(size)
                        when {
                            lw == 1280 && lh == 720 -> 0
                            lw == 1920 && lh == 1080 -> 1
                            lw == 1366 && lh == 768 -> 2
                            lw == 1440 && lh == 810 -> 3
                            lw == 960 && lh == 540 -> 4
                            else -> 100 + Math.abs(lw - 1280)
                        }
                    }
                }
                "quality", "experimental-1080p60" -> {
                    resolutionPolicyName = if (profile == "quality") "strict_quality" else "strict_1080p"
                    validSizes.addAll(supportedSizes.filter { isNearAspect(it, 16, 9) && landscapeW(it) <= 1920 })
                    validSizes.sortBy { size ->
                        val lw = landscapeW(size)
                        val lh = landscapeH(size)
                        when {
                            lw == 1920 && lh == 1080 -> 0
                            lw == 1280 && lh == 720 -> 1
                            else -> 100 + Math.abs(lw - 1920)
                        }
                    }
                }
                "native" -> {
                    resolutionPolicyName = "native_pass_through"
                    validSizes.addAll(supportedSizes)
                    validSizes.sortByDescending { landscapeW(it) * landscapeH(it) }
                }
                else -> {
                    resolutionPolicyName = "default_fallback"
                    validSizes.addAll(supportedSizes.filter { isNearAspect(it, 16, 9) && landscapeW(it) <= 1920 })
                    validSizes.sortBy { Math.abs(landscapeW(it) - 1280) }
                }
            }
            
            if (validSizes.isEmpty()) {
                fallbackUsed = true
                resolutionPolicyName = "fallback_auto_no_valid_size"
                validSizes.addAll(supportedSizes)
                validSizes.sortBy { Math.abs(landscapeW(it) - requestedWidth) }
            }
            
            StreamState.resolutionPolicy.set(resolutionPolicyName)
            StreamState.fallbackUsed.set(fallbackUsed)
            
            validSizes
        }

        val boundSize = android.util.Size(requestedWidth, requestedHeight)
        
        return ResolutionSelector.Builder()
            .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(boundSize, androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .setResolutionFilter(filter)
            .build()
    }
}
