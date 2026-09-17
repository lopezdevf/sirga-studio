// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.sirga.studio.engine.devices.AudioDeviceEntry
import com.sirga.studio.engine.model.MonitoringMode
import com.sirga.studio.engine.model.Source
import com.sirga.studio.engine.model.kind
import com.sirga.studio.engine.studio.StudioController
import com.sirga.studio.engine.studio.StudioState
import com.sirga.studio.ui.destinations.Callout
import com.sirga.studio.ui.theme.Sirga
import java.util.Locale

@Composable
fun MixerPanel(
    state: StudioState,
    /** Se lee al dibujar: 15 veces por segundo solo se repintan los medidores, no todo el mezclador. */
    levels: () -> Map<String, Float>,
    errors: Map<String, String>,
    inputs: List<AudioDeviceEntry>,
    monitorStatus: String?,
    onGain: (String, Float) -> Unit,
    onToggleMute: (String) -> Unit,
    onProperties: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.audio.isEmpty()) {
        Text("No hay fuentes de audio. Añade un micrófono o el audio interno desde Fuentes.", color = Sirga.colors.textLow, modifier = modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        monitorStatus?.let { status -> item { Callout(status, Sirga.colors.record) } }
        items(state.audio, key = { it.sourceId }) { channel ->
            val source = state.sources[channel.sourceId] ?: return@items
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(source.kind.icon, contentDescription = null, tint = Sirga.colors.textMid, modifier = Modifier.size(18.dp))
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(source.name, color = Sirga.colors.textHigh, maxLines = 1)
                        deviceLabel(source, inputs)?.let { Text(it, style = Sirga.numeric, color = Sirga.colors.textLow, maxLines = 1) }
                    }
                    if (channel.monitoring != MonitoringMode.Off) {
                        Icon(Icons.Outlined.Headphones, "Monitorizado", tint = Sirga.colors.accentText, modifier = Modifier.size(16.dp))
                    }
                    Text(
                        if (channel.muted) "MUTE" else String.format(Locale.ROOT, "%+.1f dB", channel.gainDb),
                        style = Sirga.numeric,
                        color = if (channel.muted) Sirga.colors.live else Sirga.colors.textMid,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    ToolButton(
                        if (channel.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                        if (channel.muted) "Activar sonido" else "Silenciar",
                        { onToggleMute(channel.sourceId) },
                        tint = if (channel.muted) Sirga.colors.live else Sirga.colors.textMid,
                    )
                    ToolButton(Icons.Outlined.Tune, "Ajustes de ${source.name}", { onProperties(channel.sourceId) })
                }
                errors[channel.sourceId]?.let { Text(it, style = Sirga.numeric, color = Sirga.colors.record, modifier = Modifier.padding(top = 4.dp)) }
                LevelMeter(level = { levels()[channel.sourceId] ?: 0f }, muted = channel.muted, modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp))
                Slider(
                    value = channel.gainDb,
                    onValueChange = { onGain(channel.sourceId, it) },
                    valueRange = StudioController.MIN_GAIN_DB..StudioController.MAX_GAIN_DB,
                    colors = SliderDefaults.colors(
                        thumbColor = Sirga.colors.textHigh,
                        activeTrackColor = if (channel.muted) Sirga.colors.textLow else Sirga.colors.accent,
                        inactiveTrackColor = Sirga.colors.line,
                    ),
                    modifier = Modifier.height(32.dp),
                )
            }
        }
    }
}

private fun deviceLabel(source: Source, inputs: List<AudioDeviceEntry>): String? = when (source) {
    is Source.Microphone -> source.device?.let { key -> inputs.firstOrNull { it.key == key }?.label ?: "Desconectado: ${key.productName}" } ?: "Micrófono predeterminado"
    is Source.InternalAudio -> "Audio de juegos y apps"
    else -> null
}

/** Vúmetro segmentado en tres zonas (verde < -20 dB, amarillo < -9 dB, rojo). */
@Composable
fun LevelMeter(level: () -> Float, muted: Boolean, modifier: Modifier = Modifier) {
    val c = Sirga.colors
    Canvas(modifier) {
        val level = level()
        val segments = 30
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        repeat(segments) { i ->
            val position = (i + 1f) / segments
            val zone = when {
                position < 0.66f -> c.meterLow
                position < 0.86f -> c.meterMid
                else -> c.meterHigh
            }
            val lit = !muted && position <= level
            drawRoundRect(
                color = if (lit) zone else zone.copy(alpha = 0.12f),
                topLeft = Offset(i * (w + gap), 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}
