// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.capture

import android.graphics.SurfaceTexture

/** Tamaño real del búfer y giro necesario para verlo derecho. */
data class CaptureFormat(val width: Int, val height: Int, val rotationDegrees: Int = 0)

sealed interface CaptureStatus {
    data object Starting : CaptureStatus
    data object Running : CaptureStatus
    /** Listo y esperando datos externos (p. ej. que el PC empiece a enviar). No se reintenta. */
    data class Waiting(val message: String) : CaptureStatus
    /** [deviceBusy]: el sistema no deja abrir el dispositivo mientras otro siga abierto (límite de cámaras). */
    data class Error(val message: String, val deviceBusy: Boolean = false) : CaptureStatus
}

interface CaptureListener {
    fun onFormat(format: CaptureFormat)
    fun onStatus(status: CaptureStatus)
}

/** Productor de fotogramas que escribe en una SurfaceTexture del compositor. */
interface SurfaceCapture {
    fun start(texture: SurfaceTexture, listener: CaptureListener)
    fun stop()

    /** El compositor no usa más de [fps] fotogramas por segundo: el productor puede dejar de generar el resto. */
    fun setMaxFps(fps: Int) = Unit
}
