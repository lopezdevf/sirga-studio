// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine

import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.Surface
import com.sirga.studio.engine.audio.AudioEngine
import com.sirga.studio.engine.capture.CameraCapture
import com.sirga.studio.engine.capture.ScreenCapture
import com.sirga.studio.engine.capture.ScreenProjection
import com.sirga.studio.engine.capture.UvcCapture
import com.sirga.studio.engine.devices.DeviceCatalog
import com.sirga.studio.engine.encode.AudioEncoder
import com.sirga.studio.engine.encode.AudioEncoderListener
import com.sirga.studio.engine.encode.VideoEncoder
import com.sirga.studio.engine.encode.VideoEncoderConfig
import com.sirga.studio.engine.encode.VideoEncoderListener
import com.sirga.studio.engine.model.BitrateMode
import com.sirga.studio.engine.model.CanvasConfig
import com.sirga.studio.engine.model.RecordStatus
import com.sirga.studio.engine.model.Source
import com.sirga.studio.engine.model.VideoCodecChoice
import com.sirga.studio.engine.output.DestinationRepository
import com.sirga.studio.engine.output.MultiStreamer
import com.sirga.studio.engine.output.OutputFormat
import com.sirga.studio.engine.output.PlatformCatalog
import com.sirga.studio.engine.output.Recorder
import com.sirga.studio.engine.pclink.PcCameraCapture
import com.sirga.studio.engine.pclink.PcCapture
import com.sirga.studio.engine.pclink.PcLinkHub
import com.sirga.studio.engine.render.CaptureFactory
import com.sirga.studio.engine.render.Compositor
import com.sirga.studio.engine.render.RenderWorkHint
import com.sirga.studio.engine.render.PreviewSlot
import com.sirga.studio.engine.service.StudioService
import com.sirga.studio.engine.settings.SettingsRepository
import com.sirga.studio.engine.thermal.ThermalGovernor
import com.sirga.studio.engine.thermal.ThermalLevel
import com.sirga.studio.engine.thermal.ThermalMonitor
import com.sirga.studio.engine.thermal.ThermalProfile
import com.sirga.studio.engine.studio.StudioController
import com.sirga.studio.engine.studio.StudioStore
import com.sirga.studio.engine.studio.snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/** La aplicación expone el motor para que el servicio en primer plano lo encuentre. */
interface StudioEngineHost {
    val engine: StudioEngine
}

/**
 * Orquesta el estudio: compositor, audio, codificadores, destinos, grabación y protección térmica.
 * Los codificadores solo existen mientras se emite o se graba.
 */
class StudioEngine(
    context: Context,
    val studio: StudioController,
    val settings: SettingsRepository,
    val destinations: DestinationRepository,
    val streamer: MultiStreamer,
    val devices: DeviceCatalog,
    private val store: StudioStore? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    val projection = ScreenProjection(appContext)

    val compositor = Compositor(studio, settings.settings, workHint = RenderWorkHint(appContext), captureFactory = object : CaptureFactory {
        override fun keyFor(source: Source): String = when (source) {
            // Por id real: «trasera principal» y «Cámara trasera (id 0)» son el mismo sensor
            is Source.Camera -> "camera:${devices.cameraIdFor(source.facing, source.cameraId) ?: source.facing}"
            is Source.UsbCamera -> "usb:${source.deviceName ?: "auto"}"
            is Source.Screen -> "screen"
            is Source.PcInput -> "pc:${source.port}"
            // Con el puerto y el código de su fuente PC: si cambian, la cámara se vuelve a abrir por la conexión nueva
            is Source.PcCamera -> (studio.state.value.sources[source.pcSourceId] as? Source.PcInput)
                .let { pc -> "pccam:${pc?.port}:${pc?.code}:${source.deviceId}" }
            else -> "src:${source.id}"
        }

        override fun create(source: Source, canvas: CanvasConfig) = createCapture(source, canvas)

        override fun canRunTogether(keyA: String, keyB: String): Boolean {
            val a = keyA.removePrefix("camera:")
            val b = keyB.removePrefix("camera:")
            if (a == keyA || b == keyB) return true
            // Sin la API de cámaras simultáneas (Android 11+) no se sabe: se intenta y, si el sistema la rechaza, se cierra la otra
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
            val combos = runCatching { appContext.getSystemService(CameraManager::class.java).concurrentCameraIds }.getOrNull() ?: return true
            return combos.any { a in it && b in it }
        }
    })

    /** Receptores de vídeo y audio del PC, compartidos por compositor y mezclador. */
    val pcLink = PcLinkHub(appContext)

    val audio = AudioEngine(appContext, studio, devices, settings.settings, { projectionHandle() }, pcLink)

    private val recorder = Recorder(appContext)
    private val governor = ThermalGovernor(settings.settings.value.thermal)

    private val _thermal = MutableStateFlow(ThermalProfile())
    val thermal: StateFlow<ThermalProfile> = _thermal.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Avisos para mostrar al usuario. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val outputLock = Any()
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    private var currentBitrateKbps = 0
    private var previewHolders = 0
    private var bitrateJob: Job? = null

    init {
        compositor.start()
        devices.start()
        scope.launch(Dispatchers.IO) { recorder.deleteAbandoned() }
        scope.launch { settings.settings.collectLatest { applyLimits() } }
        // La franja superior refleja el estado combinado de todos los destinos
        scope.launch {
            streamer.live.collect { statuses ->
                studio.setLive(MultiStreamer.aggregate(statuses.values), MultiStreamer.totalBitrateKbps(statuses.values))
            }
        }
        scope.launch { compositor.renderFps.collect { studio.setStats(it, 0) } }
        // Guarda escenas, fuentes y mezclador al poco de cada cambio (no en cada gesto de arrastre)
        store?.let { s ->
            scope.launch {
                studio.state.map { it.snapshot() }.distinctUntilChanged().collectLatest { snapshot ->
                    delay(800)
                    s.save(snapshot)
                }
            }
        }
        scope.launch {
            ThermalMonitor(appContext).readings().collect { reading ->
                governor.updateSettings(settings.settings.value.thermal)
                val profile = governor.evaluate(reading, System.currentTimeMillis())
                val previous = _thermal.value
                _thermal.value = profile
                applyLimits()
                if (profile.level > previous.level) profile.message?.let { _events.tryEmit(it) }
                if (profile.stopEverything && (streamer.isActive || recorder.isRecording)) {
                    stopStreaming()
                    stopRecording()
                }
            }
        }
    }

    // ---- Vista previa ----------------------------------------------------------------------

    fun setPreviewSurface(slot: PreviewSlot, surface: Surface?, width: Int, height: Int) {
        compositor.setPreviewSurface(slot, surface, width, height)
        synchronized(outputLock) {
            if (slot == PreviewSlot.Edit) {
                previewHolders = if (surface != null) 1 else 0
                updateAudioRunning()
            }
        }
    }

    // ---- Emisión ------------------------------------------------------------------------------

    /** Empezar y parar llegan desde varios hilos (botones, térmica, servicio): nunca a la vez. */
    private val controlLock = Any()

    val isStreaming: Boolean get() = streamer.isActive
    val isRecording: Boolean get() = recorder.isRecording

    /** Conecta todos los destinos activos. Devuelve un mensaje si no se pudo empezar. */
    fun startStreaming(): String? = synchronized(controlLock) {
        val enabled = destinations.destinations.value.filter { it.enabled }
        if (enabled.isEmpty()) return "Añade o activa al menos un destino para emitir."
        startForegroundService()
        val format = synchronized(outputLock) {
            ensureOutput() ?: return "No se pudo iniciar el codificador de vídeo de este móvil."
        }
        val targets = enabled.map { MultiStreamer.Target(it, destinations.publishUrl(it)) }
        streamer.start(targets, format)
        videoEncoder?.requestKeyFrame()
        startAdaptiveBitrate()
        return null
    }

    fun stopStreaming(): Unit = synchronized(controlLock) {
        streamer.stop()
        bitrateJob?.cancel()
        releaseOutputIfIdle()
    }

    fun startRecording(): String? = synchronized(controlLock) {
        if (recorder.isRecording) return null
        startForegroundService()
        synchronized(outputLock) {
            ensureOutput() ?: return "No se pudo iniciar el codificador de vídeo de este móvil."
        }
        val hasAudio = studio.state.value.audio.isNotEmpty()
        val name = runCatching {
            recorder.start(settings.settings.value.recording.folder, hasAudio, videoFormat, audioFormat)
        }.getOrElse { return "No se pudo crear el archivo: ${it.message}" }
        videoEncoder?.requestKeyFrame()
        studio.setRecord(RecordStatus.Recording(System.currentTimeMillis(), name))
        return null
    }

    fun stopRecording(): Unit = synchronized(controlLock) {
        val uri = recorder.stop()
        studio.setRecord(RecordStatus.Idle)
        if (uri != null) _events.tryEmit("Grabación guardada en Movies/${settings.settings.value.recording.folder}")
        releaseOutputIfIdle()
    }

    /** Llamado por el servicio con el permiso de captura de pantalla ya concedido. */
    fun onScreenCapturePermission(resultCode: Int, data: android.content.Intent) {
        projection.onPermissionResult(resultCode, data)
    }

    fun release() {
        stopStreaming()
        stopRecording()
        audio.stop()
        compositor.release()
        projection.release()
        devices.stop()
    }

    // ---- Salida -------------------------------------------------------------------------------

    /** Crea los codificadores si no existen y devuelve el formato para los destinos. */
    private fun ensureOutput(): OutputFormat? {
        val s = settings.settings.value
        val profile = _thermal.value
        val canvas = s.canvas
        val hevc = s.video.codec == VideoCodecChoice.H265 && destinationsSupportHevc()
        val format = OutputFormat(canvas.width, canvas.height, canvas.fps, s.audio.sampleRate, stereo = true, hevc = hevc)
        if (videoEncoder != null) return format

        val bitrate = profile.bitrate(s.video.bitrateKbps, s.thermal.minBitrateKbps)
        val video = VideoEncoder(videoListener)
        val surface = try {
            video.start(VideoEncoderConfig(canvas.width, canvas.height, canvas.fps, bitrate, s.video.keyframeSec, hevc, s.video.bitrateMode == BitrateMode.Cbr))
        } catch (e: Exception) {
            video.stop()
            return null
        }
        videoEncoder = video
        currentBitrateKbps = bitrate
        compositor.setEncoderSurface(surface, canvas.width, canvas.height)

        val audioEnc = AudioEncoder(audioListener)
        runCatching { audioEnc.start(s.audio.sampleRate, s.audio.bitrateKbps) }
            .onSuccess {
                audioEncoder = audioEnc
                audio.programSink = { pcm, pts -> audioEncoder?.encode(pcm, pts) }
            }
            .onFailure { _events.tryEmit("No se pudo iniciar el codificador de audio: se emitirá sin sonido") }
        updateAudioRunning()
        return format.copy(hevc = video.config?.hevc ?: false)
    }

    private fun releaseOutputIfIdle(): Unit = synchronized(outputLock) {
        if (streamer.isActive || recorder.isRecording) return
        compositor.setEncoderSurface(null, 0, 0)
        audio.programSink = null
        audioEncoder?.stop()
        audioEncoder = null
        // Espera a que el compositor suelte la superficie antes de liberar el codificador
        val video = videoEncoder
        videoEncoder = null
        videoFormat = null
        audioFormat = null
        scope.launch {
            delay(250)
            video?.stop()
        }
        updateAudioRunning()
        StudioService.stop(appContext)
    }

    private val videoListener = object : VideoEncoderListener {
        override fun onVideoConfig(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?, format: MediaFormat) {
            videoFormat = format
            streamer.setVideoInfo(sps, pps, vps)
            recorder.onVideoFormat(format)
        }

        override fun onVideoFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            streamer.sendVideo(buffer, info)
            recorder.writeVideo(buffer, info)
        }
    }

    private val audioListener = object : AudioEncoderListener {
        override fun onAudioFormat(format: MediaFormat, sampleRate: Int, stereo: Boolean) {
            audioFormat = format
            recorder.onAudioFormat(format)
        }

        override fun onAudioFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            streamer.sendAudio(buffer, info)
            recorder.writeAudio(buffer, info)
        }
    }

    /** El audio se captura con la vista previa abierta o mientras hay salida; si no, micrófono apagado. */
    private fun updateAudioRunning() {
        if (previewHolders > 0 || videoEncoder != null) audio.start() else audio.stop()
    }

    /** Aplica ajustes del usuario limitados por el control térmico. */
    private fun applyLimits() {
        val s = settings.settings.value
        val profile = _thermal.value
        compositor.outputFps = profile.fps(s.video.fps, s.thermal.minFps)
        compositor.previewFps = minOf(s.video.previewFps, profile.previewFps ?: Int.MAX_VALUE)
        compositor.resolutionScale = profile.resolutionScale
        compositor.singleRender = profile.singleRender
        studio.setCanvas(s.canvas)
        studio.setEncoder(s.encoder)
        studio.setThermal(profile.level, profile.throttled)
        val target = profile.bitrate(s.video.bitrateKbps, s.thermal.minBitrateKbps)
        if (videoEncoder != null && target < currentBitrateKbps) setBitrate(target)
    }

    /** Baja un 15 % si la red se congestiona y recupera un 10 % tras 10 s estable, sin pasar del límite térmico. */
    private fun startAdaptiveBitrate() {
        bitrateJob?.cancel()
        bitrateJob = scope.launch {
            var stableSince = System.currentTimeMillis()
            while (isActive) {
                delay(2_000)
                val s = settings.settings.value
                val ceiling = _thermal.value.bitrate(s.video.bitrateKbps, s.thermal.minBitrateKbps)
                val floor = minOf(s.thermal.minBitrateKbps, ceiling)
                val now = System.currentTimeMillis()
                when {
                    s.video.adaptiveBitrate && streamer.anyCongested() -> {
                        setBitrate(maxOf(floor, (currentBitrateKbps * 0.85f).toInt()))
                        stableSince = now
                    }
                    currentBitrateKbps < ceiling && now - stableSince > 10_000 -> {
                        setBitrate(minOf(ceiling, (currentBitrateKbps * 1.1f).toInt() + 50))
                        stableSince = now
                    }
                    currentBitrateKbps > ceiling -> setBitrate(ceiling)
                }
            }
        }
    }

    private fun setBitrate(kbps: Int) {
        if (kbps == currentBitrateKbps) return
        currentBitrateKbps = kbps
        videoEncoder?.setBitrate(kbps)
    }

    private fun destinationsSupportHevc(): Boolean =
        destinations.destinations.value.filter { it.enabled }.all { PlatformCatalog.byId(it.platformId).supportsHevc || PlatformCatalog.byId(it.platformId).isCustom }

    private fun startForegroundService() {
        val state = studio.state.value
        val sources = state.sources.values
        StudioService.start(
            appContext,
            camera = sources.any { it is Source.Camera || it is Source.UsbCamera },
            microphone = sources.any { it is Source.Microphone },
            screen = projection.active.value,
        )
    }

    private fun projectionHandle() = projection.currentProjection()

    // ---- Capturas -----------------------------------------------------------------------------

    private fun createCapture(source: Source, canvas: CanvasConfig) = when (source) {
        is Source.Camera -> devices.cameraIdFor(source.facing, source.cameraId)?.let { id ->
            CameraCapture(appContext, id, canvas.longSide, canvas.height > canvas.width, canvas.fps) { displayRotationDegrees() }
        }
        is Source.UsbCamera -> UvcCapture({ devices.findUsbCamera(source.deviceName) }, canvas.longSide)
        is Source.PcInput -> PcCapture(pcLink, source)
        is Source.PcCamera -> PcCameraCapture(pcLink, studio.state.value.sources[source.pcSourceId] as? Source.PcInput, source)
        is Source.Screen -> ScreenCapture(projection, canvas.longSide)
        else -> null
    }

    private fun displayRotationDegrees(): Int = when (displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
