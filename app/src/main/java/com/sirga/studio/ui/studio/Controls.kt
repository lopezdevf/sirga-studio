// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.ui.studio

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.sirga.studio.engine.settings.ThermalPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirga.studio.engine.model.LiveStatus
import com.sirga.studio.engine.studio.StudioState
import com.sirga.studio.engine.thermal.ThermalLevel
import com.sirga.studio.ui.settings.label
import java.util.Locale
import com.sirga.studio.ui.theme.Sirga
import com.sirga.studio.ui.theme.SirgaIsotipo

/** Franja superior: estado de emisión y telemetría, siempre legible de un vistazo. */
@Composable
fun StatusStrip(
    state: StudioState,
    compact: Boolean,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    thermalPolicy: ThermalPolicy = ThermalPolicy.Automatic,
    onThermalPolicy: (ThermalPolicy) -> Unit = {},
) {
    val c = Sirga.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
    ) {
        // En vertical no cabe todo: se quita la marca al emitir o grabar y el termómetro va sin texto
        if (!compact || !(state.isLive || state.isRecording)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(SirgaIsotipo, contentDescription = null, tint = c.accent, modifier = Modifier.size(width = 13.dp, height = 18.dp))
                Text("SIRGA", style = Sirga.panelLabel.copy(fontWeight = FontWeight.Bold), color = c.accent)
                if (!compact) Text("STUDIO", style = Sirga.panelLabel.copy(fontWeight = FontWeight.Normal), color = c.textHigh)
            }
        }
        StatusBadge(
            label = when (val live = state.live) {
                LiveStatus.Offline -> "FUERA DE AIRE"
                LiveStatus.Connecting -> "CONECTANDO"
                is LiveStatus.Live -> "EN DIRECTO"
                is LiveStatus.Reconnecting -> "RECONECTANDO ${live.attempt}"
                is LiveStatus.Failed -> "ERROR"
            },
            color = if (state.isLive) c.live else c.textLow,
            pulsing = state.isLive,
        )
        if (state.isRecording) StatusBadge("REC", c.record, pulsing = true)
        Box(Modifier.weight(1f))
        if (!compact) Metric("${state.canvas.width}×${state.canvas.height}")
        val fpsLow = state.stats.fps > 0 && state.stats.fps < state.canvas.fps * 0.8f
        if (!compact || !state.isLive || fpsLow) {
            Metric(String.format(Locale.ROOT, "%.0f/%d fps", state.stats.fps, state.canvas.fps), warn = fpsLow)
        }
        if (state.isLive) Metric("${state.stats.bitrateKbps} kbps")
        ThermalBadge(state, iconOnly = compact && (state.isLive || state.isRecording), thermalPolicy, onThermalPolicy, onSettings)
        Box(Modifier.size(32.dp).clickable(onClickLabel = "Ajustes", onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Settings, contentDescription = "Ajustes", tint = c.textMid, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Termómetro: con color y texto cuando el móvil se calienta; si no, un icono discreto. Al tocarlo se elige
 * al momento qué hace la protección térmica (bajar calidad sola, solo avisar o nada).
 */
@Composable
private fun ThermalBadge(
    state: StudioState,
    iconOnly: Boolean,
    policy: ThermalPolicy,
    onPolicy: (ThermalPolicy) -> Unit,
    onSettings: () -> Unit,
) {
    val level = state.thermalLevel
    var menuOpen by remember { mutableStateOf(false) }
    val color = when (level) {
        ThermalLevel.None -> if (policy == ThermalPolicy.Off) Sirga.colors.textLow else Sirga.colors.textMid
        ThermalLevel.Light -> Sirga.colors.meterMid
        ThermalLevel.Moderate -> Sirga.colors.record
        else -> Sirga.colors.live
    }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(Sirga.metrics.radiusSmall))
                .clickable(onClickLabel = "Protección térmica") { menuOpen = true }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Thermostat, contentDescription = "Temperatura: ${level.label}. Protección térmica: ${policy.shortLabel}", tint = color, modifier = Modifier.size(16.dp))
            if (!iconOnly && level != ThermalLevel.None) {
                Text(level.label.uppercase(), style = Sirga.panelLabel, color = color)
                if (state.thermalThrottled) Text(" · AHORRO", style = Sirga.panelLabel, color = color)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            Text(
                "PROTECCIÓN TÉRMICA · ${level.label.uppercase()}",
                style = Sirga.panelLabel, color = Sirga.colors.textLow,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ThermalPolicy.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.shortLabel, color = if (option == policy) Sirga.colors.accentText else Sirga.colors.textHigh)
                            Text(option.hint, fontSize = 12.sp, lineHeight = 16.sp, color = Sirga.colors.textLow)
                        }
                    },
                    leadingIcon = {
                        RadioButton(selected = option == policy, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Sirga.colors.accent))
                    },
                    onClick = { menuOpen = false; onPolicy(option) },
                )
            }
            DropdownMenuItem(text = { Text("Mínimos de fps, bitrate y batería…", color = Sirga.colors.textMid) }, onClick = { menuOpen = false; onSettings() })
        }
    }
}

private val ThermalPolicy.shortLabel: String
    get() = when (this) {
        ThermalPolicy.Automatic -> "Automática"
        ThermalPolicy.NotifyOnly -> "Solo avisar"
        ThermalPolicy.Off -> "Desactivada"
    }

private val ThermalPolicy.hint: String
    get() = when (this) {
        ThermalPolicy.Automatic -> "Baja bitrate, fps o resolución antes de que se caliente"
        ThermalPolicy.NotifyOnly -> "Avisa y tú decides si bajar la calidad"
        ThermalPolicy.Off -> "Nunca baja la calidad; el sistema puede frenar el móvil"
    }

@Composable
private fun StatusBadge(label: String, color: Color, pulsing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Una animación infinita pide fotogramas sin parar aunque no se vea: solo se crea mientras parpadea
        if (pulsing) PulsingDot(color) else Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = Sirga.panelLabel, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse.value }.background(color, CircleShape))
}

@Composable
private fun Metric(text: String, warn: Boolean = false) {
    Text(text, style = Sirga.numeric, color = if (warn) Sirga.colors.record else Sirga.colors.textMid, maxLines = 1)
}

/** Botonera de realización: modo estudio, transición, grabar y emitir. */
@Composable
fun TransportBar(
    state: StudioState,
    destinationCount: Int,
    onStudioMode: (Boolean) -> Unit,
    onTransition: () -> Unit,
    onRecord: () -> Unit,
    onGoLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Sirga.colors
    // En ventanas estrechas (pantalla partida, ventana emergente) los botones secundarios se quedan en icono
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 480.dp
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(
                label = "Estudio",
                icon = { Icon(Icons.Outlined.ViewColumn, null, tint = if (state.studioMode) c.onAccent else c.textMid, modifier = Modifier.size(18.dp)) },
                background = if (state.studioMode) c.accent else Color.Transparent,
                content = if (state.studioMode) c.onAccent else c.textMid,
                onClick = { onStudioMode(!state.studioMode) },
                iconOnly = compact,
            )
            if (state.studioMode) {
                TransportButton(
                    label = "Transición",
                    icon = { Icon(Icons.Outlined.SwapHoriz, null, tint = c.textHigh, modifier = Modifier.size(18.dp)) },
                    background = c.raised,
                    content = c.textHigh,
                    onClick = onTransition,
                    iconOnly = compact,
                )
            }
            Box(Modifier.weight(1f))
            TransportButton(
                label = if (state.isRecording) "Detener" else "Grabar",
                icon = { Box(Modifier.size(10.dp).background(c.record, if (state.isRecording) RoundedCornerShape(2.dp) else CircleShape)) },
                background = Color.Transparent,
                content = c.textHigh,
                onClick = onRecord,
                compact = compact,
            )
            TransportButton(
                label = when {
                    state.isLive -> "Terminar"
                    destinationCount > 1 -> "Emitir · $destinationCount"
                    else -> "Emitir"
                },
                icon = null,
                background = c.live,
                content = Color.White,
                onClick = onGoLive,
                emphasized = true,
                compact = compact,
            )
        }
    }
}

@Composable
private fun TransportButton(
    label: String,
    icon: (@Composable () -> Unit)?,
    background: Color,
    content: Color,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    compact: Boolean = false,
    /** Solo el icono; el texto queda para lectores de pantalla. */
    iconOnly: Boolean = false,
) {
    val shape = RoundedCornerShape(Sirga.metrics.radiusSmall)
    Row(
        Modifier
            .height(40.dp)
            .clip(shape)
            .background(background)
            .border(Sirga.metrics.hairline, if (background == Color.Transparent) Sirga.colors.line else background, shape)
            .clickable(onClickLabel = label, onClick = onClick)
            .then(if (iconOnly) Modifier.semantics { contentDescription = label } else Modifier)
            .padding(horizontal = if (compact || iconOnly) 10.dp else if (emphasized) 22.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.invoke()
        if (!iconOnly) {
            Text(
                label.uppercase(), color = content, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                letterSpacing = if (compact) 0.5.sp else 1.sp, maxLines = 1, softWrap = false,
            )
        }
    }
}
