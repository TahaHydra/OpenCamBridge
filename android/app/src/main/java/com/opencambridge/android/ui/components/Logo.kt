package com.opencambridge.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opencambridge.android.ui.Ocb

/** The OpenCamBridge mark (same drawing as the desktop app): a lens in the accent tile. */
@Composable
fun LogoMark(size: Dp = 56.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        drawRoundRect(
            color = Ocb.Accent,
            topLeft = Offset(unit, unit),
            size = androidx.compose.ui.geometry.Size(22f * unit, 22f * unit),
            cornerRadius = CornerRadius(6.5f * unit, 6.5f * unit)
        )
        drawCircle(Color.White, radius = 5.4f * unit, center = Offset(12f * unit, 12f * unit), style = Stroke(width = 2f * unit))
        drawCircle(Color.White, radius = 1.9f * unit, center = Offset(12f * unit, 12f * unit))
        drawCircle(Color.White.copy(alpha = 0.85f), radius = 1.1f * unit, center = Offset(17.6f * unit, 6.4f * unit))
    }
}
