// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraDevice.StateCallback.ERROR_CAMERA_IN_USE
import android.hardware.camera2.CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Cámara por Camera2 (incluidas las USB que el sistema expone como externas).
 * Pide la resolución más pequeña que cubre el lienzo: capturar de más solo genera calor.
 *
 * En directos largos la cámara es lo que más calienta. Siguiendo la guía de Android para cámaras con
 * control térmico: caso de uso de videollamada (el fabricante lo ajusta para sesiones largas con poco
 * consumo), reducción de ruido y nitidez rápidas, sin estabilización electrónica ni detección de caras,
 * y nunca más fotogramas de los que dibuja el compositor.
 */
class CameraCapture(
    context: Context,
    private val cameraId: String,
    private val targetLongSide: Int,
    private val canvasPortrait: Boolean,
    private val fps: Int,
    private val displayRotation: () -> Int,
) : SurfaceCapture {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("SirgaCamera-$cameraId").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    @Volatile private var stopped = false

    /** Petición en curso y datos de la cámara, para cambiar los fps sin reabrirla. Solo en el hilo de la cámara. */
    private var request: CaptureRequest.Builder? = null
    private var characteristics: CameraCharacteristics? = null
    private var maxFps = fps

    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private var formatListener: CaptureListener? = null
    private var format: CaptureFormat? = null

    /**
     * Al girar el móvil o la tablet con la cámara abierta, la app no se recrea: la orientación de la
     * imagen se corrige aquí sin reabrir la cámara.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || stopped) return
            val current = format ?: return
            val rotation = (360 - displayRotation()) % 360
            if (rotation == current.rotationDegrees) return
            val updated = current.copy(rotationDegrees = rotation)
            format = updated
            formatListener?.onFormat(updated)
        }
    }

    override fun start(texture: SurfaceTexture, listener: CaptureListener) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onStatus(CaptureStatus.Error("Falta el permiso de cámara"))
            return
        }
        listener.onStatus(CaptureStatus.Starting)
        handler.post {
            try {
                val chars = manager.getCameraCharacteristics(cameraId)
                val size = chooseSize(chars)
                texture.setDefaultBufferSize(size.width, size.height)
                val target = Surface(texture).also { surface = it }
                // La matriz de la SurfaceTexture ya gira el búfer según el sensor: el contenido llega
                // en la orientación natural del móvil, solo falta compensar si el móvil está girado
                val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val (w, h) = if (sensor % 180 != 0) size.height to size.width else size.width to size.height
                val initial = CaptureFormat(w, h, (360 - displayRotation()) % 360)
                format = initial
                formatListener = listener
                listener.onFormat(initial)
                displayManager?.registerDisplayListener(displayListener, handler)

                @Suppress("MissingPermission")
                manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (stopped) {
                            camera.close()
                            return
                        }
                        device = camera
                        runCatching { createSession(camera, target, chars, listener) }
                            .onFailure { listener.onStatus(CaptureStatus.Error("No se pudo iniciar la cámara; reintentando…")) }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        device = null
                        if (!stopped) listener.onStatus(CaptureStatus.Error("La cámara se desconectó o la usa otra app"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        device = null
                        val busy = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                        listener.onStatus(CaptureStatus.Error(errorMessage(error), deviceBusy = busy))
                    }
                })
            } catch (e: CameraAccessException) {
                val busy = e.reason == CameraAccessException.CAMERA_IN_USE || e.reason == CameraAccessException.MAX_CAMERAS_IN_USE
                listener.onStatus(CaptureStatus.Error(if (busy) errorMessage(ERROR_MAX_CAMERAS_IN_USE) else "No se pudo abrir la cámara: ${e.message}", deviceBusy = busy))
            } catch (e: Exception) {
                listener.onStatus(CaptureStatus.Error("No se pudo abrir la cámara: ${e.message}"))
            }
        }
    }

    /**
     * TEMPLATE_RECORD da exposición estable para vídeo, pero algunas cámaras (p. ej. la frontal de
     * ciertos Samsung) no lo implementan: entonces se usa TEMPLATE_PREVIEW, que es obligatorio.
     */
    private fun requestBuilder(camera: CameraDevice): CaptureRequest.Builder =
        try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        } catch (e: Exception) {
            Log.i(TAG, "Cámara $cameraId sin plantilla de grabación; se usa la de vista previa")
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        }

    private fun createSession(camera: CameraDevice, target: Surface, chars: CameraCharacteristics, listener: CaptureListener) {
        val output = OutputConfiguration(target)
        val videoCall = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && supportsVideoCallUseCase(chars)
        if (videoCall) {
            output.streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL.toLong()
        }
        Log.i(TAG, "Cámara $cameraId: modo videollamada=$videoCall, ${maxFps} fps ${bestFpsRange(chars, maxFps)}")
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (stopped) {
                        s.close()
                        return
                    }
                    session = s
                    // Los callbacks de Camera2 corren en nuestro hilo: una excepción aquí cerraría la app entera
                    try {
                        val builder = requestBuilder(camera)
                        builder.addTarget(target)
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        applyLowPower(builder, chars)
                        bestFpsRange(chars, maxFps)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        s.setRepeatingRequest(builder.build(), null, handler)
                        request = builder
                        characteristics = chars
                        listener.onStatus(CaptureStatus.Running)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cámara $cameraId: no se pudo iniciar la captura", e)
                        listener.onStatus(CaptureStatus.Error("La cámara rechazó la configuración; reintentando…"))
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onStatus(CaptureStatus.Error("La cámara no admite esta configuración"))
                }
            },
        )
        runCatching { camera.createCaptureSession(config) }
            .onFailure { listener.onStatus(CaptureStatus.Error("No se pudo iniciar la cámara: ${it.message}")) }
    }

    override fun setMaxFps(fps: Int) {
        handler.post {
            val wanted = fps.coerceIn(MIN_FPS, this.fps)
            if (wanted == maxFps || stopped) return@post
            maxFps = wanted
            val builder = request ?: return@post
            val chars = characteristics ?: return@post
            val range = bestFpsRange(chars, wanted) ?: return@post
            if (builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) == range) return@post
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            runCatching { session?.setRepeatingRequest(builder.build(), null, handler) }
                .onSuccess { Log.i(TAG, "Cámara $cameraId a $range fps") }
                .onFailure { Log.w(TAG, "Cámara $cameraId: no se pudo cambiar a $range fps", it) }
        }
    }

    /** VIDEO_CALL solo si la cámara declara que admite casos de uso; si no, la sesión fallaría. */
    private fun supportsVideoCallUseCase(chars: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE !in capabilities) return false
        val useCases = chars.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES) ?: return false
        return CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL.toLong() in useCases
    }

    /** Procesado de imagen en su modo rápido; cada ajuste solo si la cámara lo admite. */
    private fun applyLowPower(builder: CaptureRequest.Builder, chars: CameraCharacteristics) {
        fun choose(key: CaptureRequest.Key<Int>, available: CameraCharacteristics.Key<IntArray>, vararg preferred: Int) {
            val modes = chars.get(available) ?: return
            preferred.firstOrNull { it in modes }?.let { builder.set(key, it) }
        }
        choose(CaptureRequest.NOISE_REDUCTION_MODE, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
            CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        choose(CaptureRequest.EDGE_MODE, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_FAST)
        choose(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
            CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
        choose(CaptureRequest.HOT_PIXEL_MODE, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CaptureRequest.HOT_PIXEL_MODE_FAST)
        choose(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        choose(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES,
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
    }

    /** Espera a que la cámara se cierre: si se liberara antes la SurfaceTexture, la cámara escribiría en el vacío. */
    override fun stop() {
        stopped = true
        val closed = CountDownLatch(1)
        handler.post {
            runCatching { displayManager?.unregisterDisplayListener(displayListener) }
            formatListener = null
            runCatching { session?.stopRepeating() }
            runCatching { session?.close() }
            runCatching { device?.close() }
            surface?.release()
            session = null
            device = null
            surface = null
            request = null
            closed.countDown()
            // Camera2 aún avisa del cierre por este hilo: se termina un poco después
            handler.postDelayed({ thread.quitSafely() }, 1_000)
        }
        closed.await(700, TimeUnit.MILLISECONDS)
    }

    private fun chooseSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val wide = sizes.filter { it.width * 9 == it.height * 16 && maxOf(it.width, it.height) <= 1920 }
        val pool = wide.ifEmpty { sizes.filter { maxOf(it.width, it.height) <= 1920 }.ifEmpty { sizes } }

        // Si el móvil está en vertical y el lienzo en horizontal (o al revés) la imagen se recorta mucho:
        // entonces el lado corto de la cámara debe cubrir el lado largo del lienzo para no verse borrosa
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val naturalPortrait = sensor % 180 != 0
        val contentPortrait = naturalPortrait != (displayRotation() % 180 != 0)
        val mismatch = contentPortrait != canvasPortrait
        val wanted = targetLongSide.coerceIn(640, 1920)
        val candidates = if (mismatch) {
            pool.filter { minOf(it.width, it.height) >= minOf(wanted, 1080) }
        } else {
            pool.filter { maxOf(it.width, it.height) >= wanted }
        }
        return candidates.minByOrNull { it.width * it.height } ?: pool.maxBy { it.width * it.height }
    }

    private fun bestFpsRange(chars: CameraCharacteristics, fps: Int): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        // Rango fijo si existe (fps estables para el codificador); si no, el más bajo que llegue a los fps
        // pedidos, y entre esos el de mínimo más alto para que la exposición no baje los fps con poca luz
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper >= fps }.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenByDescending { it.lower })
    }

    private companion object {
        const val TAG = "SirgaCamera"
        const val MIN_FPS = 10
    }

    private fun errorMessage(error: Int) = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "La cámara está en uso por otra app"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Este móvil no permite abrir más cámaras a la vez"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "La cámara está desactivada por una política del sistema"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Error de la cámara"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "El servicio de cámara falló; reinicia la app"
        else -> "Error de cámara ($error)"
    }
}
