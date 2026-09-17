// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.pclink

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Surface
import com.sirga.studio.engine.capture.CaptureFormat
import com.sirga.studio.engine.capture.CaptureListener
import com.sirga.studio.engine.capture.CaptureStatus
import com.sirga.studio.engine.capture.SurfaceCapture
import com.sirga.studio.engine.model.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet

sealed interface PcLinkStatus {
    /** Nadie conectado. [notice] explica el último intento fallido (p. ej. código incorrecto). */
    data class Waiting(val notice: String? = null) : PcLinkStatus
    /** [viaUsb]: el PC llegó por la red del anclaje USB (cable), no por WiFi. */
    data class Connected(val pcName: String, val width: Int, val height: Int, val latencyMs: Long?, val viaUsb: Boolean = false) : PcLinkStatus
    data class Error(val message: String) : PcLinkStatus
}

/**
 * Cámara o micrófono del PC que llega por la conexión de un [PcLinkReceiver] en su propia señal.
 * Lo abren la fuente de vídeo y el mezclador; mientras alguien lo use, el PC lo sigue enviando.
 */
class PcDeviceStream internal constructor(val id: Int, val kind: PcDeviceKind, val deviceId: String, val deviceName: String) {
    internal var users = 0
    @Volatile internal var videoListener: CaptureListener? = null
    internal var decoder: PcVideoDecoder? = null
    @Volatile internal var audioListener: ((ShortArray) -> Unit)? = null
    @Volatile internal var sampleRate = 48_000
    internal val resampler = LinearResampler()
    @Volatile internal var lastKeyframeRequest = 0L
}

/** Dirección por la que el PC puede llegar al móvil. */
data class LinkAddress(val label: String, val ip: String)

/**
 * Recibe la pantalla y el sonido del PC desde Sirga Studio PC, sin capturadora ni programas de terceros. Escucha en
 * [port] por WiFi, por la zona WiFi del móvil o por anclaje USB, y solo acepta al PC que conoce
 * el [code] de la fuente.
 */
class PcLinkReceiver(
    context: Context,
    val port: Int,
    val code: String,
    private val sourceName: String,
    private val onStatus: (PcLinkStatus) -> Unit,
    private val onDevices: (List<PcDevice>) -> Unit = {},
) {
    private val appContext = context.applicationContext
    @Volatile private var server: ServerSocket? = null
    @Volatile private var session: Session? = null
    @Volatile private var released = false
    private var acceptThread: Thread? = null

    @Volatile var status: PcLinkStatus = PcLinkStatus.Waiting()
        private set

    /** true cuando el puerto está abierto: solo entonces se anuncia al PC en el descubrimiento. */
    val listening: Boolean get() = server != null && !released

    /** Estado y formato hacia la fuente de vídeo. */
    @Volatile var videoListener: CaptureListener? = null

    /** PCM estéreo 16 bits entrelazado ya convertido a [targetSampleRate]. */
    @Volatile var audioListener: ((ShortArray) -> Unit)? = null
    @Volatile var targetSampleRate = 48_000

    private val clock = ClockSync()

    private val _devices = MutableStateFlow<List<PcDevice>>(emptyList())
    /** Cámaras y micrófonos del PC conectado (vacía sin PC). */
    val devices: StateFlow<List<PcDevice>> = _devices.asStateFlow()

    private val streamLock = Any()
    private val deviceStreams = HashMap<String, PcDeviceStream>()
    private val streamsById = java.util.concurrent.ConcurrentHashMap<Int, PcDeviceStream>()

    /**
     * El ahorro de energía de la WiFi agrupa los paquetes y añade decenas o cientos de milisegundos
     * de retraso irregular; mientras el PC está conectado se pide el modo de baja latencia.
     */
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        appContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SirgaPcLink")?.apply {
            setReferenceCounted(false)
        }
    }.getOrNull()
    private var videoWidth = 0
    private var videoHeight = 0
    @Volatile private var latencyMs: Long? = null
    /** Retraso medido por el PC: si llega, manda sobre la estimación con relojes sincronizados. */
    @Volatile private var reportedLatencyMs: Long? = null
    private var lastLatencyPublish = 0L
    private var renderedFrames = 0L

    private val decoder = PcVideoDecoder(
        onSize = { w, h ->
            videoWidth = w
            videoHeight = h
            videoListener?.onFormat(CaptureFormat(w, h))
        },
        onRendered = { captureUs -> onFrameRendered(captureUs) },
    )

    val deviceName: String by lazy {
        runCatching { Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    val discoveryName: String get() = sourceName

    fun start() {
        acceptThread = Thread({ acceptLoop() }, "SirgaPcLink-$port").apply { start() }
    }

    fun setVideoSurface(surface: Surface?) {
        decoder.setSurface(surface)
        if (surface != null) session?.requestKeyframe()
    }

    fun release() {
        released = true
        runCatching { wifiLock?.release() }
        runCatching { server?.close() }
        session?.close()
        session = null
        acceptThread?.join(500)
        decoder.release()
        synchronized(streamLock) { deviceStreams.values.toList() }.forEach { it.decoder?.release() }
    }

    /** Pide al PC [deviceId] en una señal propia (o comparte la que ya está abierta). */
    fun openStream(kind: PcDeviceKind, deviceId: String, deviceName: String): PcDeviceStream {
        val stream = synchronized(streamLock) {
            val key = "${kind.code}:$deviceId"
            deviceStreams[key]?.also { it.users++ } ?: run {
                val id = (1..255).first { !streamsById.containsKey(it) }
                PcDeviceStream(id, kind, deviceId, deviceName).also { created ->
                    created.users = 1
                    if (kind == PcDeviceKind.Camera) {
                        created.decoder = PcVideoDecoder(
                            onSize = { w, h -> created.videoListener?.onFormat(CaptureFormat(w, h)) },
                            onRendered = {},
                        )
                    }
                    deviceStreams[key] = created
                    streamsById[id] = created
                }
            }
        }
        session?.sendSubscriptions()
        return stream
    }

    fun closeStream(stream: PcDeviceStream) {
        synchronized(streamLock) {
            if (--stream.users > 0) return
            deviceStreams.remove("${stream.kind.code}:${stream.deviceId}")
            streamsById.remove(stream.id)
        }
        stream.videoListener = null
        stream.audioListener = null
        stream.decoder?.release()
        session?.sendSubscriptions()
    }

    fun setStreamVideo(stream: PcDeviceStream, surface: Surface?, listener: CaptureListener?) {
        stream.videoListener = listener
        listener?.onStatus(streamStatus(stream))
        stream.decoder?.setSurface(surface)
        if (surface != null) session?.requestStreamKeyframe(stream)
    }

    fun setStreamAudio(stream: PcDeviceStream, sampleRate: Int, listener: ((ShortArray) -> Unit)?) {
        stream.sampleRate = sampleRate
        stream.audioListener = listener
    }

    private fun streamStatus(stream: PcDeviceStream): CaptureStatus = when (val s = status) {
        is PcLinkStatus.Error -> CaptureStatus.Error(s.message)
        is PcLinkStatus.Waiting -> CaptureStatus.Waiting(s.notice ?: "Abre Sirga Studio PC en el ordenador y conecta la fuente «$sourceName» · código $code")
        is PcLinkStatus.Connected ->
            if (_devices.value.none { it.id == stream.deviceId }) CaptureStatus.Waiting("«${stream.deviceName}» no está conectada a «${s.pcName}»")
            else CaptureStatus.Running
    }

    private fun refreshStreamStatuses() {
        synchronized(streamLock) { deviceStreams.values.toList() }.forEach { stream ->
            stream.videoListener?.onStatus(streamStatus(stream))
        }
    }

    private fun acceptLoop() {
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        } catch (e: IOException) {
            publish(PcLinkStatus.Error("El puerto $port lo está usando otra app o el propio sistema del móvil. Cambia el puerto en las propiedades de la fuente."))
            return
        }
        server = socket
        publish(PcLinkStatus.Waiting())
        while (!released) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            // El último PC que se conecta gana: si Sirga Studio PC se reinicia, no hay que esperar a que caduque la conexión vieja
            session?.close()
            session = Session(client).also { it.start() }
        }
        runCatching { socket.close() }
    }

    private fun onFrameRendered(captureUs: Long) {
        // Uno de cada diez fotogramas vuelve al PC para que mida el retraso real de punta a punta
        if (renderedFrames++ % 10 == 0L) session?.frameShown(captureUs)
        val estimate = clock.latencyMs(captureUs, SystemClock.elapsedRealtimeNanos() / 1000)
        if (estimate != null) latencyMs = latencyMs?.let { (it * 7 + estimate) / 8 } ?: estimate
        val shown = reportedLatencyMs ?: latencyMs ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLatencyPublish >= 500) {
            lastLatencyPublish = now
            (status as? PcLinkStatus.Connected)?.let { publish(it.copy(width = videoWidth, height = videoHeight, latencyMs = shown)) }
        }
    }

    private fun publish(newStatus: PcLinkStatus) {
        status = newStatus
        onStatus(newStatus)
        when (newStatus) {
            is PcLinkStatus.Waiting -> videoListener?.onStatus(CaptureStatus.Waiting(waitingMessage(newStatus.notice)))
            is PcLinkStatus.Connected -> videoListener?.onStatus(CaptureStatus.Running)
            is PcLinkStatus.Error -> videoListener?.onStatus(CaptureStatus.Error(newStatus.message))
        }
        refreshStreamStatuses()
    }

    fun waitingMessage(notice: String? = (status as? PcLinkStatus.Waiting)?.notice): String =
        notice ?: "Abre Sirga Studio PC en el ordenador y elige este móvil · código $code"

    private inner class Session(private val socket: Socket) : Thread("SirgaPcSession-$port") {
        @Volatile private var closed = false
        private lateinit var output: DataOutputStream
        private var lastKeyframeRequest = 0L
        private val resampler = LinearResampler()

        override fun run() {
            try {
                socket.tcpNoDelay = true
                socket.receiveBufferSize = 1 shl 20
                // Sin paquetes en este tiempo, el PC se ha ido (manda la hora cada segundo aunque la pantalla no cambie)
                socket.soTimeout = 5_000
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
                output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256))

                val hello = SirgaLink.readHello(input) ?: return
                if (hello.code != code.toIntOrNull()) {
                    synchronized(this) { SirgaLink.writeReply(output, SirgaLink.RESULT_WRONG_CODE, deviceName) }
                    if (session === this) publish(PcLinkStatus.Waiting("«${hello.pcName}» intentó conectar con un código incorrecto · código $code"))
                    return
                }
                synchronized(this) { SirgaLink.writeReply(output, SirgaLink.RESULT_OK, deviceName) }
                clock.reset()
                latencyMs = null
                reportedLatencyMs = null
                Log.i(TAG, "PC conectado: ${hello.pcName}")
                runCatching { wifiLock?.acquire() }
                val viaUsb = runCatching { NetworkInterface.getByInetAddress(socket.localAddress)?.name?.lowercase() }.getOrNull()
                    ?.let { it.startsWith("rndis") || it.startsWith("usb") || it.startsWith("ncm") } == true
                publish(PcLinkStatus.Connected(hello.pcName, videoWidth, videoHeight, null, viaUsb))
                requestKeyframe()
                sendSubscriptions()
                // La hora se pide cada segundo desde otro hilo: mide el retraso y mantiene viva la conexión
                // aunque la pantalla del PC no cambie y no suene nada
                Thread({
                    while (!closed) {
                        sendTimeRequest()
                        try {
                            sleep(1_000)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }, "SirgaPcClock").apply {
                    isDaemon = true
                    start()
                }

                while (!closed) handle(SirgaLink.readPacket(input))
            } catch (e: SocketTimeoutException) {
                Log.i(TAG, "El PC dejó de enviar")
            } catch (e: IOException) {
                // Conexión cerrada por el PC o por la red
            } finally {
                runCatching { socket.close() }
                if (session === this) runCatching { wifiLock?.release() }
                if (session === this && !released) {
                    session = null
                    // Sin conexión no llegan fotogramas: un decodificador abierto solo gasta batería y calienta
                    decoder.pause()
                    synchronized(streamLock) { deviceStreams.values.toList() }.forEach { it.decoder?.pause() }
                    _devices.value = emptyList()
                    onDevices(emptyList())
                    if (status is PcLinkStatus.Connected) publish(PcLinkStatus.Waiting())
                }
            }
        }

        private fun handle(packet: SirgaLink.Packet) {
            val data = ByteBuffer.wrap(packet.payload)
            when (packet.type) {
                SirgaLink.VIDEO_FORMAT -> if (packet.payload.size >= 5) {
                    decoder.setSize(data.short.toInt() and 0xFFFF, data.short.toInt() and 0xFFFF)
                }
                SirgaLink.VIDEO_FRAME -> if (packet.payload.size > 9) {
                    val keyframe = data.get().toInt() and 1 == 1
                    val captureUs = data.long
                    val frame = packet.payload.copyOfRange(9, packet.payload.size)
                    if (!decoder.decode(frame, keyframe, captureUs)) requestKeyframe()
                }
                SirgaLink.AUDIO_PCM -> if (packet.payload.size > 13) {
                    val rate = data.int
                    val channels = data.get().toInt().coerceAtLeast(1)
                    data.long // instante de captura: el audio se mezcla al llegar, igual que el vídeo
                    deliverAudio(data.slice().order(ByteOrder.LITTLE_ENDIAN), rate, channels)
                }
                SirgaLink.LATENCY_REPORT -> if (packet.payload.size >= 4) {
                    reportedLatencyMs = (data.int.toLong() and 0xFFFFFFFFL)
                }
                SirgaLink.TIME_REPLY -> if (packet.payload.size >= 16) {
                    clock.add(phoneSentUs = data.long, pcUs = data.long, phoneReceivedUs = SystemClock.elapsedRealtimeNanos() / 1000)
                }
                SirgaLink.DEVICE_LIST -> runCatching { SirgaLink.parseDeviceList(packet.payload) }.getOrNull()?.let { list ->
                    _devices.value = list
                    onDevices(list)
                    refreshStreamStatuses()
                    // El PC ya tiene la lista lista: se repite lo que se quiere por si la primera petición llegó mientras preparaba la captura
                    sendSubscriptions()
                }
                SirgaLink.STREAM_VIDEO_FORMAT -> if (packet.payload.size >= 6) {
                    val stream = streamsById[data.get().toInt() and 0xFF]
                    stream?.decoder?.setSize(data.short.toInt() and 0xFFFF, data.short.toInt() and 0xFFFF)
                }
                SirgaLink.STREAM_VIDEO_FRAME -> if (packet.payload.size > 10) {
                    val stream = streamsById[data.get().toInt() and 0xFF] ?: return
                    val keyframe = data.get().toInt() and 1 == 1
                    val captureUs = data.long
                    val decoder = stream.decoder ?: return
                    if (!decoder.decode(packet.payload.copyOfRange(10, packet.payload.size), keyframe, captureUs)) requestStreamKeyframe(stream)
                }
                SirgaLink.STREAM_AUDIO_PCM -> if (packet.payload.size > 14) {
                    val stream = streamsById[data.get().toInt() and 0xFF] ?: return
                    val listener = stream.audioListener ?: return
                    val rate = data.int
                    val channels = data.get().toInt().coerceAtLeast(1)
                    data.long
                    toStereo(data.slice().order(ByteOrder.LITTLE_ENDIAN), channels)?.let { listener(stream.resampler.convert(it, rate, stream.sampleRate)) }
                }
            }
        }

        private fun deliverAudio(pcm: ByteBuffer, rate: Int, channels: Int) {
            val listener = audioListener ?: return
            toStereo(pcm, channels)?.let { listener(resampler.convert(it, rate, targetSampleRate)) }
        }

        private fun toStereo(pcm: ByteBuffer, channels: Int): ShortArray? {
            val shorts = pcm.asShortBuffer()
            val frames = shorts.remaining() / channels
            if (frames == 0) return null
            val stereo = ShortArray(frames * 2)
            for (f in 0 until frames) {
                val l = shorts.get(f * channels)
                stereo[f * 2] = l
                stereo[f * 2 + 1] = if (channels > 1) shorts.get(f * channels + 1) else l
            }
            return stereo
        }

        /** Lista completa de cámaras y micrófonos que se usan ahora: el PC abre las nuevas y cierra las demás. */
        fun sendSubscriptions() {
            val wanted = synchronized(streamLock) { deviceStreams.values.associate { it.id to it.deviceId } }
            send(SirgaLink.SUBSCRIBE, SirgaLink.subscribe(wanted))
        }

        fun requestStreamKeyframe(stream: PcDeviceStream) {
            val now = SystemClock.elapsedRealtime()
            if (now - stream.lastKeyframeRequest < 300) return
            stream.lastKeyframeRequest = now
            send(SirgaLink.STREAM_KEYFRAME_REQUEST, byteArrayOf(stream.id.toByte()))
        }

        fun frameShown(captureUs: Long) = send(SirgaLink.FRAME_SHOWN, SirgaLink.frameShown(captureUs))

        fun requestKeyframe() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastKeyframeRequest < 300) return
            lastKeyframeRequest = now
            send(SirgaLink.KEYFRAME_REQUEST)
        }

        private fun sendTimeRequest() = send(SirgaLink.TIME_REQUEST, SirgaLink.timeRequest(SystemClock.elapsedRealtimeNanos() / 1000))

        private fun send(type: Int, payload: ByteArray = ByteArray(0)) {
            if (closed || !::output.isInitialized) return
            try {
                synchronized(this) { SirgaLink.writePacket(output, type, payload) }
            } catch (e: IOException) {
                close()
            }
        }

        fun close() {
            closed = true
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val TAG = "SirgaPcLink"
    }
}

/**
 * Responde a Sirga Studio PC cuando busca móviles en la red: así el usuario elige el móvil de una
 * lista en lugar de escribir direcciones IP.
 */
internal class PcLinkDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val receivers = CopyOnWriteArraySet<PcLinkReceiver>()
    private var thread: Thread? = null
    @Volatile private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun register(receiver: PcLinkReceiver) {
        receivers += receiver
        if (thread == null) start()
    }

    @Synchronized
    fun unregister(receiver: PcLinkReceiver) {
        receivers -= receiver
        if (receivers.isEmpty()) stop()
    }

    private fun start() {
        // Algunos móviles filtran los paquetes de difusión con la pantalla apagada si no se pide este bloqueo
        multicastLock = runCatching {
            appContext.getSystemService(WifiManager::class.java)?.createMulticastLock("SirgaPcLink")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        thread = Thread({ loop() }, "SirgaPcDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    private fun stop() {
        runCatching { socket?.close() }
        socket = null
        thread = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun loop() {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(SirgaLink.DISCOVERY_PORT))
            }
        } catch (e: IOException) {
            Log.w("SirgaPcDiscovery", "No se pudo escuchar el descubrimiento", e)
            return
        }
        socket = s
        val buffer = ByteArray(64)
        while (socket === s) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                if (!SirgaLink.isDiscoveryQuery(packet.data, packet.length)) continue
                for (receiver in receivers) {
                    if (!receiver.listening) continue
                    val reply = SirgaLink.discoveryReply(receiver.port, receiver.deviceName, receiver.discoveryName)
                    s.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                }
            } catch (e: IOException) {
                if (socket !== s) break
            }
        }
        runCatching { s.close() }
    }
}

/** Direcciones y textos de ayuda del enlace con el PC. */
object PcLinkAddresses {

    /**
     * Primer puerto desde [from] que no usa otra fuente ([taken]) y que se puede abrir en este móvil:
     * algunos fabricantes tienen servicios del sistema escuchando en puertos como el 9000.
     */
    fun firstFreePort(from: Int, taken: Set<Int>): Int =
        (from until from + 100).firstOrNull { port ->
            port !in taken && runCatching {
                ServerSocket().use {
                    it.reuseAddress = true
                    it.bind(InetSocketAddress(port))
                }
            }.isSuccess
        } ?: generateSequence(from) { it + 1 }.first { it !in taken }

    /** Direcciones IPv4 útiles: WiFi, zona WiFi del móvil y anclaje USB. */
    fun localAddresses(): List<LinkAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { addr ->
                    val name = nif.name.lowercase()
                    val label = when {
                        name.startsWith("wlan") -> "WiFi"
                        name.startsWith("swlan") || name.startsWith("ap") -> "Zona WiFi del móvil"
                        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> "Cable USB (anclaje)"
                        name.startsWith("eth") -> "Ethernet"
                        else -> null
                    }
                    label?.let { LinkAddress(it, addr.hostAddress.orEmpty()) }
                }
            }
            .filterNotNull()
    }.getOrDefault(emptyList())
}

/** Remuestreo lineal estéreo, suficiente para pasar de 44,1 kHz a 48 kHz sin artefactos audibles en voz y juegos. */
internal class LinearResampler {
    private var position = 0.0
    private var lastL = 0
    private var lastR = 0

    fun reset() {
        position = 0.0
        lastL = 0
        lastR = 0
    }

    fun convert(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || fromRate <= 0 || toRate <= 0) return input
        val inFrames = input.size / 2
        val step = fromRate.toDouble() / toRate
        val out = ArrayList<Short>((inFrames * toRate / fromRate + 2) * 2)
        while (position < inFrames) {
            val i = position.toInt()
            val frac = position - i
            val l0 = if (i == 0) lastL else input[(i - 1) * 2].toInt()
            val r0 = if (i == 0) lastR else input[(i - 1) * 2 + 1].toInt()
            val l1 = input[i * 2].toInt()
            val r1 = input[i * 2 + 1].toInt()
            out += (l0 + (l1 - l0) * frac).toInt().toShort()
            out += (r0 + (r1 - r0) * frac).toInt().toShort()
            position += step
        }
        position -= inFrames
        lastL = input[(inFrames - 1) * 2].toInt()
        lastR = input[(inFrames - 1) * 2 + 1].toInt()
        return out.toShortArray()
    }
}

/**
 * Comparte un receptor por fuente entre el compositor (vídeo) y el mezclador (audio): el puerto
 * solo se abre una vez aunque las dos partes lo usen.
 *
 * Cuando nadie lo usa, el receptor sigue abierto [GRACE_MS] antes de cerrarse: abrir las
 * propiedades, cambiar de escena o salir un momento de la app no desconecta al PC. Mientras
 * tanto no se decodifica nada, así que no gasta batería.
 */
class PcLinkHub(private val context: Context) {
    private class Entry(val receiver: PcLinkReceiver, var users: Int, var closeAt: Long = 0L)

    private val entries = HashMap<String, Entry>()
    private val discovery = PcLinkDiscovery(context)
    private val closer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "SirgaPcLinkHub").apply { isDaemon = true } }

    private val _statuses = MutableStateFlow<Map<String, PcLinkStatus>>(emptyMap())
    /** Estado del enlace de cada fuente PC activa, para mostrar conexión y retraso. */
    val statuses: StateFlow<Map<String, PcLinkStatus>> = _statuses.asStateFlow()

    private val _devices = MutableStateFlow<Map<String, List<PcDevice>>>(emptyMap())
    /** Cámaras y micrófonos de cada PC conectado, por id de su fuente PC. */
    val devices: StateFlow<Map<String, List<PcDevice>>> = _devices.asStateFlow()

    @Synchronized
    fun acquire(source: Source.PcInput): PcLinkReceiver {
        entries[source.id]?.let { entry ->
            if (entry.receiver.port == source.port && entry.receiver.code == source.code) {
                entry.users++
                entry.closeAt = 0L
                return entry.receiver
            }
            close(source.id, entry.receiver)
        }
        // Una fuente borrada o con otro puerto puede seguir en su tiempo de gracia ocupando este puerto
        entries.entries.filter { it.value.users <= 0 && it.value.receiver.port == source.port }.map { it.key to it.value.receiver }
            .forEach { (id, receiver) -> close(id, receiver) }
        val receiver = PcLinkReceiver(
            context, source.port, source.code, source.name,
            onStatus = { status -> _statuses.update { it + (source.id to status) } },
            onDevices = { list -> _devices.update { it + (source.id to list) } },
        )
        receiver.start()
        discovery.register(receiver)
        entries[source.id] = Entry(receiver, 1)
        return receiver
    }

    @Synchronized
    fun release(sourceId: String, receiver: PcLinkReceiver) {
        val entry = entries[sourceId]
        if (entry == null || entry.receiver !== receiver) {
            discovery.unregister(receiver)
            receiver.release()
            return
        }
        entry.users--
        if (entry.users > 0) return
        val closeAt = SystemClock.elapsedRealtime() + GRACE_MS
        entry.closeAt = closeAt
        closer.schedule({ closeIfUnused(sourceId, receiver, closeAt) }, GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun closeIfUnused(sourceId: String, receiver: PcLinkReceiver, closeAt: Long) {
        val entry = entries[sourceId] ?: return
        if (entry.receiver === receiver && entry.users <= 0 && entry.closeAt == closeAt) close(sourceId, receiver)
    }

    private fun close(sourceId: String, receiver: PcLinkReceiver) {
        discovery.unregister(receiver)
        receiver.release()
        entries.remove(sourceId)
        _statuses.update { it - sourceId }
        _devices.update { it - sourceId }
    }

    private companion object {
        const val GRACE_MS = 60_000L
    }
}

/** Parte de vídeo de la fuente «PC»: el decodificador escribe en la textura del compositor. */
class PcCapture(private val hub: PcLinkHub, private val source: Source.PcInput) : SurfaceCapture {
    private var receiver: PcLinkReceiver? = null
    private var surface: Surface? = null

    override fun start(texture: SurfaceTexture, listener: CaptureListener) {
        val r = hub.acquire(source)
        receiver = r
        r.videoListener = listener
        listener.onStatus(
            when (val s = r.status) {
                is PcLinkStatus.Connected -> CaptureStatus.Running.also {
                    if (s.width > 0) listener.onFormat(CaptureFormat(s.width, s.height))
                }
                is PcLinkStatus.Error -> CaptureStatus.Error(s.message)
                is PcLinkStatus.Waiting -> CaptureStatus.Waiting(r.waitingMessage(s.notice))
            }
        )
        val s = Surface(texture)
        surface = s
        r.setVideoSurface(s)
    }

    override fun stop() {
        receiver?.let {
            it.videoListener = null
            it.setVideoSurface(null)
            hub.release(source.id, it)
        }
        receiver = null
        surface?.release()
        surface = null
    }
}

/** Vídeo de una cámara conectada al PC: comparte la conexión de su fuente PC ([pc]). */
class PcCameraCapture(
    private val hub: PcLinkHub,
    private val pc: Source.PcInput?,
    private val source: Source.PcCamera,
) : SurfaceCapture {
    private var receiver: PcLinkReceiver? = null
    private var stream: PcDeviceStream? = null
    private var surface: Surface? = null

    override fun start(texture: SurfaceTexture, listener: CaptureListener) {
        if (pc == null) {
            listener.onStatus(CaptureStatus.Error("Falta la fuente «PC» por la que llega esta cámara"))
            return
        }
        val r = hub.acquire(pc)
        val s = r.openStream(PcDeviceKind.Camera, source.deviceId, source.deviceName)
        receiver = r
        stream = s
        val out = Surface(texture)
        surface = out
        r.setStreamVideo(s, out, listener)
    }

    override fun stop() {
        val r = receiver
        val s = stream
        if (r != null && s != null) {
            r.setStreamVideo(s, null, null)
            r.closeStream(s)
            pc?.let { hub.release(it.id, r) }
        }
        receiver = null
        stream = null
        surface?.release()
        surface = null
    }
}
