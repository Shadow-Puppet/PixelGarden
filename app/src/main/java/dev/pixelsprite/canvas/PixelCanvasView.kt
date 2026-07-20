package dev.pixelsprite.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.pixelsprite.tools.InputPoint
import dev.pixelsprite.tools.ToolKind
import dev.pixelsprite.ui.EditorSession
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The editing surface. Deliberately NOT Compose (per spec): composites the
 * active frame into an in-memory Bitmap, then blits with nearest-neighbor
 * scaling (Paint.isFilterBitmap = false) under a zoom/pan matrix.
 *
 * Gesture arbitration:
 *  - one pointer          -> draw (stylus or finger; finger blocked while a
 *                            stylus is in range if palm rejection is on)
 *  - two pointers         -> pinch zoom + pan (cancels an in-flight stroke)
 *  - two-finger quick tap -> undo
 *
 * Pressure and tilt are read from every MotionEvent and passed down the
 * pipeline from v1 even though dynamics only use them later (per spec).
 */
class PixelCanvasView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var session: EditorSession? = null
        set(value) {
            field = value
            value?.addListener { requestRender() }
            allocBuffers()
            requestRender()
        }

    // ---- render state -------------------------------------------------

    private var docBitmap: Bitmap? = null
    private var compositeBuf: IntArray = IntArray(0)
    private var displayBuf: IntArray = IntArray(0)
    private val blitPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val matrix = Matrix()

    var scale = 8f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set
    private var fittedOnce = false

    init {
        holder.addCallback(this)
    }

    private fun allocBuffers() {
        val doc = session?.doc ?: return
        val n = doc.width * doc.height
        if (compositeBuf.size != n) {
            compositeBuf = IntArray(n)
            displayBuf = IntArray(n)
            docBitmap = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        }
    }

    fun requestRender() {
        allocBuffers()
        val doc = session?.doc ?: return
        val bmp = docBitmap ?: return
        if (!holder.surface.isValid) return

        dev.pixelsprite.model.Compositor.composite(doc, session!!.activeFrame, compositeBuf)
        bakeCheckerboard(doc.width, doc.height)
        bmp.setPixels(displayBuf, 0, doc.width, 0, 0, doc.width, doc.height)

        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(0xFF181818.toInt())
            matrix.reset()
            matrix.postScale(scale, scale)
            matrix.postTranslate(offsetX, offsetY)
            canvas.drawBitmap(bmp, matrix, blitPaint)
            drawCanvasBorder(canvas, doc.width, doc.height)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /** Blend the composite over a transparency checkerboard for display. */
    private fun bakeCheckerboard(w: Int, h: Int) {
        val light = 0xFF3A3A3A.toInt()
        val dark = 0xFF2C2C2C.toInt()
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = compositeBuf[row + x]
                val a = c ushr 24
                val bg = if (((x / 4) + (y / 4)) and 1 == 0) light else dark
                displayBuf[row + x] = when (a) {
                    255 -> c
                    0 -> bg
                    else -> blend(c, a, bg)
                }
            }
        }
    }

    private fun blend(src: Int, a: Int, dst: Int): Int {
        val inv = 255 - a
        val r = (((src ushr 16) and 0xFF) * a + ((dst ushr 16) and 0xFF) * inv) / 255
        val g = (((src ushr 8) and 0xFF) * a + ((dst ushr 8) and 0xFF) * inv) / 255
        val b = ((src and 0xFF) * a + (dst and 0xFF) * inv) / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE; color = Color.DKGRAY; strokeWidth = 2f
    }

    private fun drawCanvasBorder(canvas: Canvas, w: Int, h: Int) {
        canvas.drawRect(offsetX, offsetY, offsetX + w * scale, offsetY + h * scale, borderPaint)
    }

    private fun fitToView() {
        val doc = session?.doc ?: return
        if (width == 0 || height == 0) return
        val s = minOf(width.toFloat() / doc.width, height.toFloat() / doc.height) * 0.85f
        scale = maxOf(1f, s)
        offsetX = (width - doc.width * scale) / 2f
        offsetY = (height - doc.height * scale) / 2f
        fittedOnce = true
    }

    // ---- SurfaceHolder ------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!fittedOnce) fitToView()
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (!fittedOnce) fitToView()
        requestRender()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    // ---- input --------------------------------------------------------

    private enum class Mode { NONE, DRAW, TRANSFORM }
    private var mode = Mode.NONE
    private var stylusInStroke = false
    private var lastSpan = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var twoFingerDownTime = 0L
    private var twoFingerMoved = false

    private fun toDocX(vx: Float): Int = Math.floor(((vx - offsetX) / scale).toDouble()).toInt()
    private fun toDocY(vy: Float): Int = Math.floor(((vy - offsetY) / scale).toDouble()).toInt()

    private fun inputPoint(e: MotionEvent, index: Int) = InputPoint(
        x = toDocX(e.getX(index)),
        y = toDocY(e.getY(index)),
        pressure = e.getPressure(index),
        tiltX = e.getAxisValue(MotionEvent.AXIS_TILT, index),
        tiltY = e.getOrientation(index),
    )

    private fun isStylus(e: MotionEvent, index: Int): Boolean {
        val t = e.getToolType(index)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = session ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val stylus = isStylus(e, 0)
                stylusInStroke = stylus
                // Stylus with the eraser end selects the eraser behavior implicitly.
                if (stylus && e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER &&
                    s.tool == ToolKind.PENCIL
                ) {
                    // one-shot: draw this stroke as eraser without changing UI tool
                    // (kept simple for v1 — full mapping is a QoL refinement)
                }
                mode = Mode.DRAW
                s.strokeBegin(inputPoint(e, 0))
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && !stylusInStroke) {
                    // Switch to transform: cancel any partial finger stroke.
                    if (mode == Mode.DRAW) s.strokeCancel()
                    mode = Mode.TRANSFORM
                    lastSpan = span(e)
                    lastFocusX = focusX(e); lastFocusY = focusY(e)
                    twoFingerDownTime = System.currentTimeMillis()
                    twoFingerMoved = false
                } else if (stylusInStroke && s.palmRejection && !isStylus(e, e.actionIndex)) {
                    // Palm rejection: ignore finger touches while stylus draws.
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> {
                    // Feed all historical samples for smooth strokes.
                    for (h in 0 until e.historySize) {
                        s.strokeMove(
                            InputPoint(
                                x = toDocX(e.getHistoricalX(0, h)),
                                y = toDocY(e.getHistoricalY(0, h)),
                                pressure = e.getHistoricalPressure(0, h),
                            )
                        )
                    }
                    s.strokeMove(inputPoint(e, 0))
                }
                Mode.TRANSFORM -> {
                    if (e.pointerCount >= 2) {
                        val sp = span(e)
                        val fx = focusX(e); val fy = focusY(e)
                        if (abs(sp - lastSpan) > 4f || hypot(fx - lastFocusX, fy - lastFocusY) > 4f) {
                            twoFingerMoved = true
                        }
                        if (lastSpan > 0f) {
                            val factor = (sp / lastSpan).coerceIn(0.5f, 2f)
                            zoomAt(fx, fy, factor)
                        }
                        offsetX += fx - lastFocusX
                        offsetY += fy - lastFocusY
                        lastSpan = sp; lastFocusX = fx; lastFocusY = fy
                        requestRender()
                    }
                }
                Mode.NONE -> {}
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TRANSFORM && e.pointerCount == 2) {
                    // Two-finger quick tap with no movement = undo gesture.
                    if (!twoFingerMoved &&
                        System.currentTimeMillis() - twoFingerDownTime < 250
                    ) {
                        s.doUndo()
                    }
                    mode = Mode.NONE
                }
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW) s.strokeEnd()
                mode = Mode.NONE
                stylusInStroke = false
            }

            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAW) s.strokeCancel()
                mode = Mode.NONE
                stylusInStroke = false
            }
        }
        return true
    }

    private fun span(e: MotionEvent): Float =
        hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

    private fun focusX(e: MotionEvent): Float = (e.getX(0) + e.getX(1)) / 2f
    private fun focusY(e: MotionEvent): Float = (e.getY(0) + e.getY(1)) / 2f

    private fun zoomAt(fx: Float, fy: Float, factor: Float) {
        val newScale = (scale * factor).coerceIn(1f, 128f)
        val actual = newScale / scale
        offsetX = fx - (fx - offsetX) * actual
        offsetY = fy - (fy - offsetY) * actual
        scale = newScale
    }
}
