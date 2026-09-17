// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.pclink

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.sirga.studio.engine.encode.NalUnits

/**
 * Decodifica el H.264 del PC por hardware directamente sobre la textura del compositor.
 *
 * Sin búfer de espera: cada fotograma entra al decodificador en cuanto llega y se muestra en
 * cuanto sale. Si el decodificador acumula varios, solo se muestra el más reciente.
 */
internal class PcVideoDecoder(
    private val onSize: (width: Int, height: Int) -> Unit,
    private val onRendered: (captureUs: Long) -> Unit,
) {
    private val lock = Any()
    private var codec: MediaCodec? = null
    private var output: Thread? = null
    @Volatile private var running = false
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var width = 0
    private var height = 0

    /** Tras perder un fotograma, los siguientes dependen de él: se espera al próximo clave. */
    private var needKeyframe = true

    fun setSurface(newSurface: Surface?) = synchronized(lock) {
        if (newSurface === surface) return@synchronized
        stopCodec()
        surface = newSurface
        needKeyframe = true
    }

    fun setSize(newWidth: Int, newHeight: Int) = synchronized(lock) {
        if (newWidth == width && newHeight == height) return@synchronized
        width = newWidth
        height = newHeight
        onSize(newWidth, newHeight)
    }

    /** Devuelve false si hace falta pedir un fotograma clave al PC. */
    fun decode(frame: ByteArray, keyframe: Boolean, captureUs: Long): Boolean = synchronized(lock) {
        val target = surface ?: return true // nadie mira la fuente: no se gasta batería decodificando
        if (needKeyframe && !keyframe) return false

        if (keyframe) {
            val nals = NalUnits.split(frame)
            val newSps = nals.firstOrNull { nalType(it) == NAL_SPS }
            val newPps = nals.firstOrNull { nalType(it) == NAL_PPS }
            if (newSps != null && newPps != null && (codec == null || !newSps.contentEquals(sps))) {
                // Primer fotograma o cambio de resolución en el PC
                stopCodec()
                if (!startCodec(target, newSps, newPps)) return false
            }
        }
        val c = codec ?: return false
        try {
            val index = c.dequeueInputBuffer(INPUT_WAIT_US)
            if (index < 0) {
                needKeyframe = true
                return false
            }
            val buffer = c.getInputBuffer(index) ?: return false
            if (buffer.capacity() < frame.size) {
                c.queueInputBuffer(index, 0, 0, captureUs, 0)
                needKeyframe = true
                return false
            }
            buffer.clear()
            buffer.put(frame)
            c.queueInputBuffer(index, 0, frame.size, captureUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            needKeyframe = false
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decodificador detenido", e)
            stopCodec()
            needKeyframe = true
            false
        }
    }

    /**
     * El PC se desconectó: se libera el decodificador (y su hilo de vaciado) pero se conserva la superficie
     * para retomar con el primer fotograma clave de la próxima conexión.
     */
    fun pause() = synchronized(lock) {
        stopCodec()
        needKeyframe = true
    }

    fun release() = synchronized(lock) {
        stopCodec()
        surface = null
    }

    private fun startCodec(target: Surface, newSps: ByteArray, newPps: ByteArray): Boolean {
        val w = width.takeIf { it > 0 } ?: 1920
        val h = height.takeIf { it > 0 } ?: 1080
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setByteBuffer("csd-0", NalUnits.withStartCode(newSps))
            setByteBuffer("csd-1", NalUnits.withStartCode(newPps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(w * h, 1 shl 20))
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // Qualcomm y otros fabricantes: entregar cada fotograma en cuanto se decodifica
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
        }
        return try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val caps = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            c.configure(format, target, null, 0)
            c.start()
            codec = c
            sps = newSps
            running = true
            output = Thread({ drain(c) }, "SirgaPcDecoder").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar el decodificador", e)
            codec = null
            false
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        running = false
        output?.join(500)
        output = null
        runCatching { c.stop() }
        runCatching { c.release() }
        codec = null
        sps = null
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = c.dequeueOutputBuffer(info, OUTPUT_WAIT_US)
                when {
                    index >= 0 -> {
                        var current = index
                        var captureUs = info.presentationTimeUs
                        // Si ya hay otro fotograma listo, el anterior llega tarde: se descarta
                        while (true) {
                            val next = c.dequeueOutputBuffer(info, 0)
                            if (next < 0) {
                                if (next == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) reportFormat(c.outputFormat)
                                break
                            }
                            c.releaseOutputBuffer(current, false)
                            current = next
                            captureUs = info.presentationTimeUs
                        }
                        c.releaseOutputBuffer(current, true)
                        onRendered(captureUs)
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportFormat(c.outputFormat)
                }
            }
        } catch (e: IllegalStateException) {
            // Se detuvo el códec mientras se vaciaba
        }
    }

    private fun reportFormat(format: MediaFormat) {
        val w = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        else format.getInteger(MediaFormat.KEY_WIDTH)
        val h = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        else format.getInteger(MediaFormat.KEY_HEIGHT)
        if (w > 0 && h > 0) onSize(w, h)
    }

    private fun nalType(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    private companion object {
        const val TAG = "SirgaPcDecoder"
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val INPUT_WAIT_US = 20_000L
        const val OUTPUT_WAIT_US = 10_000L
    }
}
