package com.opencambridge.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opencambridge.android.ui.Ocb
import com.opencambridge.android.ui.SectionLabelStyle
import com.opencambridge.android.ui.TelemetryStyle

/** Status vocabulary shared by dots, pills and notices. */
enum class Tone { Ok, Warn, Danger, Idle, Busy, Accent }

fun Tone.color(): Color = when (this) {
    Tone.Ok -> Ocb.Ok
    Tone.Warn, Tone.Busy -> Ocb.Warn
    Tone.Danger -> Ocb.Danger
    Tone.Accent -> Ocb.Accent
    Tone.Idle -> Ocb.Text4
}

@Composable
fun StatusDot(tone: Tone, size: Dp = 8.dp) {
    val alpha = if (tone == Tone.Busy) {
        rememberInfiniteTransition(label = "busy").animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "busyAlpha"
        ).value
    } else 1f
    Box(
        modifier = Modifier
            .size(size)
            .alpha(alpha)
            .background(tone.color(), CircleShape)
    )
}

/** Dot + label in a pill: the one-glance state of the phone. */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, onDark: Boolean = false) {
    Row(
        modifier = modifier
            .background(if (onDark) Color(0xB3000000) else Ocb.Surface2, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(tone, 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Ocb.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The single primary action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
) {
    val active = enabled && !loading
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(if (active || loading) Ocb.Accent else Ocb.Surface3)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (active || loading) Color.White else Ocb.Text3)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
    height: Dp = 52.dp,
) {
    val content = when {
        !enabled -> Ocb.Text4
        danger -> Ocb.DangerText
        else -> Ocb.Text
    }
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(if (danger) Ocb.DangerSoft else Ocb.Surface2)
            .border(1.dp, if (danger) Ocb.Danger.copy(alpha = 0.35f) else Ocb.Border2, RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** A round control with its label underneath (switch camera, torch, dim). */
@Composable
fun RoundToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onDark: Boolean = false,
) {
    Column(
        modifier = modifier.widthIn(min = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    when {
                        active -> Ocb.Accent
                        onDark -> Color(0x99000000)
                        else -> Ocb.Surface2
                    }
                )
                .border(1.dp, if (active) Color.Transparent else Ocb.Border2, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    !enabled -> Ocb.Text4
                    active -> Color.White
                    else -> Ocb.Text
                },
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Ocb.Text2 else Ocb.Text4,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/** Top app bar for secondary screens. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ocb.Text)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ocb.Text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A titled card holding related rows. */
@Composable
fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = SectionLabelStyle,
                color = Ocb.Text3,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ocb.RadiusCard))
                .background(Ocb.Surface)
                .border(1.dp, Ocb.Border, RoundedCornerShape(Ocb.RadiusCard)),
            content = content
        )
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = Ocb.Text3,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)
            )
        }
    }
}

@Composable
fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(Ocb.Border)
    )
}

/** Tappable row leading to another page. */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailingText: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Ocb.Surface3, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Ocb.Text2, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ocb.Text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ocb.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailingText != null) {
            Text(trailingText, style = MaterialTheme.typography.bodyMedium, color = Ocb.Text3, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ocb.Text4)
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .defaultMinSize(minHeight = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Ocb.Text else Ocb.Text3)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ocb.Text3)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ocb.Accent,
                checkedBorderColor = Ocb.Accent,
                uncheckedThumbColor = Ocb.Text2,
                uncheckedTrackColor = Ocb.Surface3,
                uncheckedBorderColor = Ocb.Border2
            )
        )
    }
}

/** Label on the left, value on the right; for read-only facts. */
@Composable
fun ValueRow(label: String, value: String, tone: Tone? = null, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ocb.Text2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = if (mono) TelemetryStyle else MaterialTheme.typography.bodyMedium,
            color = when (tone) {
                Tone.Ok -> Ocb.Ok
                Tone.Warn, Tone.Busy -> Ocb.Warn
                Tone.Danger -> Ocb.DangerText
                Tone.Accent -> Ocb.AccentText
                Tone.Idle -> Ocb.Text3
                null -> Ocb.Text
            },
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 240.dp)
        )
    }
}

/** Two to four mutually exclusive choices, all visible at once. */
@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Ocb.Bg, RoundedCornerShape(Ocb.RadiusControl))
            .border(1.dp, Ocb.Border, RoundedCornerShape(Ocb.RadiusControl))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) Ocb.Surface4 else Color.Transparent)
                    .clickable(enabled = enabled && !isSelected, role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        !enabled -> Ocb.Text4
                        isSelected -> Ocb.Text
                        else -> Ocb.Text3
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A labelled segmented control inside a settings card. */
@Composable
fun <T> SegmentedRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Ocb.Text else Ocb.Text3)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ocb.Text3)
        }
        Spacer(Modifier.height(10.dp))
        SegmentedControl(options, selected, onSelect, enabled = enabled)
    }
}

/** A row that opens a menu of choices. */
@Composable
fun <T> DropdownRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    emptyText: String = "None available",
    subtitle: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled && options.isNotEmpty(), role = Role.DropdownList) { expanded = true }
                .defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Ocb.Text else Ocb.Text3)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ocb.Text3)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                current ?: emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Ocb.AccentText else Ocb.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = if (enabled) Ocb.Text3 else Ocb.Text4)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Ocb.Surface2)
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (value == selected) Ocb.AccentText else Ocb.Text) },
                    trailingIcon = if (value == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = Ocb.AccentText) }
                    } else null,
                    onClick = {
                        expanded = false
                        if (value != selected) onSelect(value)
                    }
                )
            }
        }
    }
}

/**
 * A labelled slider. `onChange` updates the thumb; `onCommit` fires once when
 * the finger lifts, so phone-side settings are not flooded with requests.
 */
@Composable
fun SliderRow(
    title: String,
    readout: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit = {},
    steps: Int = 0,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Ocb.Text else Ocb.Text3, modifier = Modifier.weight(1f))
            Text(readout, style = TelemetryStyle, color = if (enabled) Ocb.Text2 else Ocb.Text4)
        }
        OcbSlider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            steps = steps,
            enabled = enabled
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ocb.Text3)
        }
    }
}

/** The app's slider: a round thumb on a slim accent track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcbSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Ocb.Accent,
        inactiveTrackColor = Ocb.Surface4,
        disabledThumbColor = Ocb.Text4,
        disabledActiveTrackColor = Ocb.Text4,
        disabledInactiveTrackColor = Ocb.Surface3,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent
    )
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        steps = steps,
        valueRange = valueRange,
        thumb = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .shadow(2.dp, CircleShape)
                    .background(if (enabled) Color.White else Ocb.Text4, CircleShape)
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(4.dp),
                enabled = enabled,
                colors = colors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp
            )
        }
    )
}

/** A tinted message card for warnings, errors and tips. */
@Composable
fun Notice(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
) {
    val tint = tone.color()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ocb.RadiusControl))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(Ocb.RadiusControl))
            .padding(14.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (tone == Tone.Danger) Ocb.DangerText else Ocb.Text)
                Spacer(Modifier.height(2.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = Ocb.Text2)
        }
    }
}
