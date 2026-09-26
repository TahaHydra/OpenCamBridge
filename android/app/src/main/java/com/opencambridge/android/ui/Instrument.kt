package com.opencambridge.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What a lamp is reporting. Colour carries the same meaning as on a mixer. */
enum class Lamp { Off, Ready, Live, Warn, Fail, Busy }

private fun Lamp.color(): Color = when (this) {
    Lamp.Off -> Ocb.Ink4
    Lamp.Ready -> Ocb.Ready
    Lamp.Live -> Ocb.Tally
    Lamp.Warn -> Ocb.Warn
    Lamp.Fail -> Ocb.Fail
    Lamp.Busy -> Ocb.Warn
}

/** An indicator lamp with a glow, so it reads as illuminated rather than filled. */
@Composable
fun LampDot(state: Lamp, size: Int = 10) {
    val blink = if (state == Lamp.Busy) {
        val transition = rememberInfiniteTransition(label = "lamp")
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
            label = "lampAlpha"
        ).value
    } else {
        1f
    }
    val color by animateColorAsState(state.color(), label = "lampColor")
    Box(
        modifier = Modifier
            .size((size + 8).dp)
            .alpha(blink),
        contentAlignment = Alignment.Center
    ) {
        if (state != Lamp.Off) {
            // The halo is what makes it read as a lamp instead of a dot.
            Box(
                modifier = Modifier
                    .size((size + 8).dp)
                    .background(
                        Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent)),
                        RoundedCornerShape(50)
                    )
            )
        }
        Box(modifier = Modifier.size(size.dp).background(color, RoundedCornerShape(50)))
    }
}

/**
 * The tally bar: the phone's answer to "is this actually working", legible from
 * across a desk. It illuminates deep red only when a desktop client is really
 * pulling frames — not merely when the camera is running.
 */
@Composable
fun TallyBar(state: Lamp, title: String, note: String, modifier: Modifier = Modifier) {
    val accent = state.color()
    val background = when (state) {
        Lamp.Live -> Brush.horizontalGradient(listOf(Color(0xFF3A1210), Color(0xFF210A0A)))
        Lamp.Ready -> Brush.horizontalGradient(listOf(Color(0xFF12201A), Color(0xFF0F1414)))
        Lamp.Busy -> Brush.horizontalGradient(listOf(Color(0xFF241C0C), Color(0xFF14120C)))
        Lamp.Fail -> Brush.horizontalGradient(listOf(Color(0xFF2A1210), Color(0xFF190B0A)))
        else -> Brush.horizontalGradient(listOf(Ocb.PanelHigh, Ocb.Panel))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(Ocb.CornerPanel))
            .border(
                1.dp,
                if (state == Lamp.Off) Ocb.Rule2 else accent.copy(alpha = 0.5f),
                RoundedCornerShape(Ocb.CornerPanel)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LampDot(state, size = 12)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title.uppercase(),
                style = TallyStyle,
                color = if (state == Lamp.Off) Ocb.Ink3 else accent
            )
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(note, style = MaterialThemeBodySmall(), color = Ocb.Ink2)
        }
    }
}

// Small indirection so callers do not each import MaterialTheme just for a style.
@Composable
private fun MaterialThemeBodySmall() = androidx.compose.material3.MaterialTheme.typography.bodySmall

/** Uppercase legend with a hairline running out to the edge, like a rack face. */
@Composable
fun Legend(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = LegendStyle, color = Ocb.Ink2)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Ocb.Rule2, Color.Transparent)))
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** A graphite panel with a top highlight, the app's basic container. */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScopeAlias.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Ocb.Panel, Color(0xFF11141A))),
                RoundedCornerShape(Ocb.CornerPanel)
            )
            .border(1.dp, accent ?: Ocb.Rule, RoundedCornerShape(Ocb.CornerPanel))
            .padding(16.dp),
        content = content
    )
}

typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/** A section: legend, then content, inside a panel. */
@Composable
fun Section(
    legend: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScopeAlias.() -> Unit
) {
    PanelCard(modifier = modifier, accent = accent) {
        Legend(legend, trailing = trailing)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** One row of telemetry: label left, monospaced value right. */
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier, tone: Color = Ocb.Ink) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = Ocb.Ink3,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            style = TelemetryStyle,
            color = tone,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.2f)
        )
    }
}

/**
 * A stage of the pipeline with the rate it is sustaining. Three of these across
 * the Stream tab tell the user WHERE frames are being lost, which is the same
 * job the desktop's signal chain does.
 */
@Composable
fun StageMeter(
    name: String,
    value: String,
    unit: String,
    state: Lamp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Ocb.Inset, RoundedCornerShape(Ocb.CornerControl))
            .border(1.dp, Ocb.Rule, RoundedCornerShape(Ocb.CornerControl))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LampDot(state, size = 7)
            Text(
                name.uppercase(),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = if (state == Lamp.Off) Ocb.Ink4 else Ocb.Ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            style = ReadoutLargeStyle,
            color = when (state) {
                Lamp.Off -> Ocb.Ink4
                Lamp.Warn, Lamp.Busy -> Ocb.Warn
                Lamp.Fail -> Ocb.Fail
                else -> Ocb.Ink
            }
        )
        if (unit.isNotBlank()) {
            Text(
                unit.uppercase(),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = Ocb.Ink4
            )
        }
    }
}

/**
 * Segmented control. Replaces a dropdown wherever there are two or three
 * choices: on a phone a dropdown costs two taps and hides the alternatives,
 * and hiding "the other codec" is how a whole feature became unreachable.
 */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Ocb.Inset, RoundedCornerShape(Ocb.CornerControl))
            .border(1.dp, Ocb.Rule, RoundedCornerShape(Ocb.CornerControl))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) Brush.verticalGradient(listOf(Ocb.PanelTop, Ocb.PanelHigh))
                        else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
                        RoundedCornerShape(4.dp)
                    )
                    .clickable(enabled = enabled && !active) { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label.uppercase(),
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = when {
                        !enabled -> Ocb.Ink4
                        active -> Ocb.Ink
                        else -> Ocb.Ink3
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * A value picker for lists too long or too variable for a segmented control
 * (lenses, resolutions). Panel-styled rather than an OutlinedTextField, which
 * reads as an editable field when the value is actually read-only.
 */
@Composable
fun <T> Picker(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emptyText: String = "None available",
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            style = FieldLabelStyle,
            color = if (enabled) Ocb.Ink2 else Ocb.Ink4
        )
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ocb.Inset, RoundedCornerShape(Ocb.CornerControl))
                    .border(1.dp, Ocb.Rule2, RoundedCornerShape(Ocb.CornerControl))
                    .clickable(enabled = enabled && options.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    current ?: emptyText,
                    style = ReadoutStyle,
                    color = when {
                        !enabled -> Ocb.Ink4
                        current == null -> Ocb.Warn
                        else -> Ocb.Ink
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("▾", style = ReadoutStyle, color = Ocb.Ink3)
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Ocb.PanelHigh)
            ) {
                options.forEach { (value, text) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                text,
                                style = ReadoutStyle,
                                color = if (value == selected) Ocb.Signal else Ocb.Ink
                            )
                        },
                        onClick = {
                            expanded = false
                            if (value != selected) onSelect(value)
                        }
                    )
                }
            }
        }
    }
}

/** A labelled fader with a monospaced readout of its current value. */
@Composable
fun Fader(
    label: String,
    readout: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    onChange: (Float) -> Unit,
    note: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(),
                style = FieldLabelStyle,
                color = if (enabled) Ocb.Ink2 else Ocb.Ink4,
                modifier = Modifier.weight(1f)
            )
            Text(readout, style = ReadoutStyle, color = if (enabled) Ocb.Ink else Ocb.Ink4)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Ocb.Key,
                activeTrackColor = Ocb.Signal,
                inactiveTrackColor = Ocb.Inset,
                disabledThumbColor = Ocb.Ink4,
                disabledActiveTrackColor = Ocb.Ink4,
                disabledInactiveTrackColor = Ocb.Inset
            )
        )
        if (note != null) {
            Text(note, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = Ocb.Ink3)
        }
    }
}

/** A labelled switch row with an optional explanatory note. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = if (enabled) Ocb.Ink else Ocb.Ink3
            )
            if (note != null) {
                Text(
                    note,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = Ocb.Ink3
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ocb.Void,
                checkedTrackColor = Ocb.Signal,
                checkedBorderColor = Ocb.Signal,
                uncheckedThumbColor = Ocb.Ink3,
                uncheckedTrackColor = Ocb.Inset,
                uncheckedBorderColor = Ocb.Rule2
            )
        )
    }
}

/** A small status pill. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = Ocb.Ink2,
    lamp: Lamp? = null,
) {
    Row(
        modifier = modifier
            .background(Ocb.PanelHigh.copy(alpha = 0.85f), RoundedCornerShape(50))
            .border(1.dp, tone.copy(alpha = 0.32f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (lamp != null) {
            LampDot(lamp, size = 6)
            Spacer(Modifier.width(2.dp))
        }
        Text(
            text.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = tone,
            maxLines = 1
        )
    }
}

/** The primary illuminated key: one large, unmissable action. */
@Composable
fun KeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    val fill = when {
        !enabled -> Brush.verticalGradient(listOf(Ocb.PanelTop, Ocb.PanelHigh))
        danger -> Brush.verticalGradient(listOf(Color(0xFF3A1614), Color(0xFF25100F)))
        else -> Brush.verticalGradient(listOf(Color(0xFFFBFCFD), Color(0xFFC8D0DC)))
    }
    val content = when {
        !enabled -> Ocb.Ink4
        danger -> Color(0xFFFFB3AC)
        else -> Ocb.Void
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(fill, RoundedCornerShape(Ocb.CornerControl))
            .border(
                1.dp,
                if (danger) Ocb.Fail.copy(alpha = 0.5f) else Ocb.Rule2,
                RoundedCornerShape(Ocb.CornerControl)
            )
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text.uppercase(),
            style = LegendStyle.copy(fontSize = 16.sp, letterSpacing = 2.sp),
            color = content
        )
    }
}

/** An inset well, for logs and dense diagnostics. */
@Composable
fun Well(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScopeAlias.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Ocb.Inset, RoundedCornerShape(Ocb.CornerControl))
            .border(BorderStroke(1.dp, Ocb.Rule), RoundedCornerShape(Ocb.CornerControl))
            .padding(padding),
        content = content
    )
}
