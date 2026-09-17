// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.render

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.util.Log

/**
 * Pistas de rendimiento de Android (ADPF) para el hilo del compositor: se dice al sistema cuánto debe
 * durar cada fotograma y cuánto duró de verdad, y así ajusta la CPU a lo justo en lugar de subirla por
 * si acaso. Sin la API (Android 11 o anterior) no hace nada.
 */
class RenderWorkHint(context: Context) {
    private val manager: PerformanceHintManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(PerformanceHintManager::class.java) else null
    private var session: PerformanceHintManager.Session? = null
    private var targetFps = 0
    private var powerEfficient: Boolean? = null

    fun start(threadId: Int, fps: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val m = manager ?: return
        session = runCatching { m.createHintSession(intArrayOf(threadId), NANOS_PER_SECOND / fps) }
            .onFailure { Log.i(TAG, "Sin pistas de rendimiento", it) }
            .getOrNull()
        targetFps = fps
    }

    fun report(workNanos: Long, fps: Int, preferPowerEfficiency: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val s = session ?: return
        runCatching {
            if (fps != targetFps) {
                s.updateTargetWorkDuration(NANOS_PER_SECOND / fps.coerceAtLeast(1))
                targetFps = fps
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && powerEfficient != preferPowerEfficiency) {
                s.setPreferPowerEfficiency(preferPowerEfficiency)
                powerEfficient = preferPowerEfficiency
            }
            s.reportActualWorkDuration(workNanos.coerceAtLeast(1))
        }.onFailure {
            Log.w(TAG, "Pistas de rendimiento desactivadas", it)
            close()
        }
    }

    fun close() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching { session?.close() }
        session = null
    }

    private companion object {
        const val TAG = "SirgaRenderHint"
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
