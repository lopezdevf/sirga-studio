// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors

package com.sirga.studio.engine.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.Matrix
import android.text.Layout as TextLayout
import android.text.StaticLayout
import android.text.TextPaint
import com.sirga.studio.engine.capture.CaptureFormat
import com.sirga.studio.engine.capture.CaptureListener
import com.sirga.studio.engine.capture.CaptureStatus
import com.sirga.studio.engine.capture.SurfaceCapture
import com.sirga.studio.engine.gl.GlRenderer
import com.sirga.studio.engine.model.SceneItem
import com.sirga.studio.engine.model.Source
import com.sirga.studio.engine.model.TextAlignment
import java.util.concurrent.ExecutorService

/** Matrices reutilizadas por fotograma para no generar basura en el hilo de render. */
internal class Matrices {
    val mvp = FloatArray(16)
    val tex = FloatArray(16)
    private val model = FloatArray(16)
    private val a = FloatArray(16)
    private val b = FloatArray(16)
    private val op = FloatArray(16)

    fun mvp(projection: FloatArray, quad: PixelRect, rotationDegrees: Float): FloatArray {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, quad.x + quad.width / 2f, quad.y + quad.height / 2f, 0f)
        if (rotationDegrees != 0f) Matrix.rotateM(model, 0, rotationDegrees, 0f, 0f, 1f)
        Matrix.translateM(model, 0, -quad.width / 2f, -quad.height / 2f, 0f)
        Matrix.scaleM(model, 0, quad.width, quad.height, 1f)
        Matrix.multiplyMM(mvp, 0, projection, 0, model, 0)
        return mvp
    }

    /** Textura 2D (Bitmap): el origen ya está arriba, solo se recorta. */
    fun bitmapTex(uv: UvRect): FloatArray {
        uvRect(uv, tex)
        return tex
    }

    /** Lienzo del framebuffer: GL guarda la fila superior al final, así que se invierte Y. */
    fun framebufferTex(): FloatArray {
        flipY(tex)
        return tex
    }

    /**
     * Textura externa: uv de contenido derecho → espejo → giro del búfer → convención GL → matriz
     * de la SurfaceTexture.
     */
    fun externalTex(st: FloatArray, rotationDegrees: Int, mirror: Boolean, uv: UvRect): FloatArray {
        uvRect(uv, a)
        if (mirror) {
            Matrix.setIdentityM(op, 0)
            Matrix.translateM(op, 0, 1f, 0f, 0f)
            Matrix.scaleM(op, 0, -1f, 1f, 1f)
            Matrix.multiplyMM(b, 0, op, 0, a, 0)
            b.copyInto(a)
        }
        if (rotationDegrees % 360 != 0) {
            Matrix.setIdentityM(op, 0)
            Matrix.translateM(op, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(op, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(op, 0, -0.5f, -0.5f, 0f)
            Matrix.multiplyMM(b, 0, op, 0, a, 0)
            b.copyInto(a)
        }
        flipY(op)
        Matrix.multiplyMM(b, 0, op, 0, a, 0)
        Matrix.multiplyMM(tex, 0, st, 0, b, 0)
        return tex
    }

    private fun uvRect(uv: UvRect, out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, uv.u, uv.v, 0f)
        Matrix.scaleM(out, 0, uv.width, uv.height, 1f)
    }

    private fun flipY(out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, 0f, 1f, 0f)
        Matrix.scaleM(out, 0, 1f, -1f, 1f)
    }
}

/** Dibuja una fuente dentro de su caja. Vive y muere en el hilo del compositor. */
internal abstract class SourceRenderer(val sourceId: String) {
    open fun update(source: Source, boxWidthPx: Int, boxHeightPx: Int) = Unit

    open fun setMaxFps(fps: Int) = Unit

    /** [source] se pasa en cada dibujado porque varias fuentes pueden compartir este renderizador. */
    abstract fun draw(gl: GlRenderer, item: SceneItem, source: Source, box: PixelRect, projection: FloatArray, alpha: Float, m: Matrices)

    abstract fun release()
}

/** Cámara, cámara USB, pantalla o PC: el productor escribe en una SurfaceTexture compartida. */
internal class ExternalRenderer(
    sourceId: String,
    private val key: String,
    private val capture: SurfaceCapture,
    private val onStatus: (String, CaptureStatus) -> Unit,
) : SourceRenderer(sourceId) {

    private val textureId = GlRenderer.createExternalTexture()
    private val surfaceTexture = SurfaceTexture(textureId)
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile private var format: CaptureFormat? = null
    @Volatile private var frameAvailable = false
    private var hasFrame = false

    init {
        surfaceTexture.setOnFrameAvailableListener { frameAvailable = true }
        capture.start(surfaceTexture, object : CaptureListener {
            override fun onFormat(format: CaptureFormat) {
                this@ExternalRenderer.format = format
            }

            override fun onStatus(status: CaptureStatus) = onStatus(key, status)
        })
    }

    override fun update(source: Source, boxWidthPx: Int, boxHeightPx: Int) {
        if (frameAvailable) {
            frameAvailable = false
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            hasFrame = true
        }
    }

    override fun draw(gl: GlRenderer, item: SceneItem, source: Source, box: PixelRect, projection: FloatArray, alpha: Float, m: Matrices) {
        val f = format ?: return
        if (!hasFrame) return
        val (rotationOffset, mirror) = when (source) {
            is Source.Camera -> source.rotationOffset to source.mirror
            is Source.UsbCamera -> source.rotationOffset to source.mirror
            else -> 0 to false
        }
        val rotation = (f.rotationDegrees + rotationOffset) % 360
        val (cw, ch) = Layout.rotatedSize(f.width, f.height, rotation)
        val placement = Layout.place(box, cw, ch, item.transform.crop, item.transform.fit)
        gl.drawTexture(
            textureId, isExternal = true,
            mvp = m.mvp(projection, placement.quad, item.transform.rotation),
            texMatrix = m.externalTex(stMatrix, rotation, mirror, placement.uv),
            alpha = alpha,
        )
    }

    override fun setMaxFps(fps: Int) = capture.setMaxFps(fps)

    override fun release() {
        capture.stop()
        surfaceTexture.setOnFrameAvailableListener(null)
        surfaceTexture.release()
        GlRenderer.deleteTexture(textureId)
    }
}

/** Imagen o texto: se rasteriza en un Bitmap fuera del hilo de render y se sube como textura. */
internal class BitmapRenderer(
    sourceId: String,
    private val worker: ExecutorService,
    private val maxLongSide: Int,
) : SourceRenderer(sourceId) {

    private val textureId = GlRenderer.createTexture2d()
    private var currentKey: Any? = null
    private var width = 0
    private var height = 0
    @Volatile private var pending: Pair<Any, Bitmap>? = null
    @Volatile private var requestedKey: Any? = null

    override fun update(source: Source, boxWidthPx: Int, boxHeightPx: Int) {
        val key: Any = when (source) {
            is Source.Image -> source.uri
            // El texto se rasteriza al tamaño de su caja para que siempre se vea nítido
            is Source.Text -> listOf(source.text, source.colorArgb, source.backgroundArgb, source.bold, source.alignment, boxWidthPx / 8, boxHeightPx / 8)
            else -> return
        }
        pending?.let { (k, bitmap) ->
            pending = null
            GlRenderer.uploadBitmap(textureId, bitmap)
            width = bitmap.width
            height = bitmap.height
            currentKey = k
            bitmap.recycle()
        }
        if (key != currentKey && key != requestedKey) {
            requestedKey = key
            worker.execute {
                val bitmap = when (source) {
                    is Source.Image -> decodeImage(source.uri, maxLongSide)
                    is Source.Text -> renderText(source, boxWidthPx, boxHeightPx)
                }
                if (bitmap != null && requestedKey == key) pending = key to bitmap
            }
        }
    }

    override fun draw(gl: GlRenderer, item: SceneItem, source: Source, box: PixelRect, projection: FloatArray, alpha: Float, m: Matrices) {
        if (currentKey == null) return
        val placement = Layout.place(box, width, height, item.transform.crop, item.transform.fit)
        gl.drawTexture(
            textureId, isExternal = false,
            mvp = m.mvp(projection, placement.quad, item.transform.rotation),
            texMatrix = m.bitmapTex(placement.uv),
            alpha = alpha,
        )
    }

    override fun release() {
        pending?.second?.recycle()
        pending = null
        GlRenderer.deleteTexture(textureId)
    }

    private fun decodeImage(path: String, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun renderText(source: Source.Text, boxW: Int, boxH: Int): Bitmap {
        val w = boxW.coerceIn(16, 2048)
        val h = boxH.coerceIn(16, 2048)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(source.backgroundArgb.toInt())
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = source.colorArgb.toInt()
            typeface = if (source.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val padding = (h * 0.08f).toInt()
        val innerW = (w - padding * 2).coerceAtLeast(8)
        val align = when (source.alignment) {
            TextAlignment.Start -> TextLayout.Alignment.ALIGN_NORMAL
            TextAlignment.Center -> TextLayout.Alignment.ALIGN_CENTER
            TextAlignment.End -> TextLayout.Alignment.ALIGN_OPPOSITE
        }
        // Busca el mayor tamaño de letra que cabe en la caja
        var size = h * 0.7f
        var layout: StaticLayout
        do {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(source.text, 0, source.text.length, paint, innerW)
                .setAlignment(align)
                .setIncludePad(false)
                .build()
            size *= 0.9f
        } while ((layout.height > h - padding * 2 || widestLine(layout) > innerW) && size > 6f)
        canvas.save()
        canvas.translate(padding.toFloat(), ((h - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun widestLine(layout: StaticLayout): Float = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
}

internal class ColorRenderer(sourceId: String) : SourceRenderer(sourceId) {
    private var argb: Long = 0xFF000000

    override fun update(source: Source, boxWidthPx: Int, boxHeightPx: Int) {
        if (source is Source.SolidColor) argb = source.argb
    }

    override fun draw(gl: GlRenderer, item: SceneItem, source: Source, box: PixelRect, projection: FloatArray, alpha: Float, m: Matrices) {
        gl.drawColor(m.mvp(projection, box, item.transform.rotation), argb, alpha)
    }

    override fun release() = Unit
}
