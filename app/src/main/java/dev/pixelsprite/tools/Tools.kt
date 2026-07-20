package dev.pixelsprite.tools

import dev.pixelsprite.model.Cel
import dev.pixelsprite.model.ColorMode
import dev.pixelsprite.model.Document

enum class ToolKind { PENCIL, ERASER, FILL, EYEDROPPER }

/** A single input sample. Pressure/tilt are captured from v1 per spec, even
 *  where a tool doesn't use them yet (dynamics come in QoL). */
data class InputPoint(
    val x: Int, val y: Int,
    val pressure: Float = 1f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
)

/**
 * Shared stroke machinery: Bresenham interpolation between samples, brush
 * stamping, and pixel-perfect mode.
 *
 * Pixel-perfect: with the classic algorithm, when three consecutive plotted
 * pixels form an "L" (middle pixel orthogonally adjacent to both neighbors,
 * neighbors diagonal to each other), the middle pixel is dropped — removing
 * the double pixels freehand/touch input produces.
 */
class StrokeEngine(
    private val doc: Document,
    private val cel: Cel,
    var brushSize: Int = 1,
    var pixelPerfect: Boolean = true,
) {
    /** Value written into the cel: ARGB in RGBA mode, palette index in INDEXED. */
    var paintValue: Int = 0

    private var lastX = -1
    private var lastY = -1
    // Path of plotted centers, for pixel-perfect correction (size-1 brush only,
    // matching Aseprite's behavior).
    private val path = ArrayList<IntArray>()
    // What each plotted center overwrote, so a dropped pixel can be restored.
    private val overwritten = HashMap<Long, Int>()

    fun begin(p: InputPoint) {
        path.clear(); overwritten.clear()
        lastX = p.x; lastY = p.y
        plot(p.x, p.y)
    }

    fun move(p: InputPoint) {
        if (p.x == lastX && p.y == lastY) return
        bresenham(lastX, lastY, p.x, p.y)
        lastX = p.x; lastY = p.y
    }

    fun end() { path.clear(); overwritten.clear() }

    private fun bresenham(x0: Int, y0: Int, x1: Int, y1: Int) {
        var x = x0; var y = y0
        val dx = Math.abs(x1 - x0); val dy = -Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (x != x0 || y != y0) plot(x, y)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun plot(cx: Int, cy: Int) {
        stamp(cx, cy)
        if (pixelPerfect && brushSize == 1) {
            path.add(intArrayOf(cx, cy))
            correctPixelPerfect()
        }
    }

    private fun correctPixelPerfect() {
        if (path.size < 3) return
        val a = path[path.size - 3]
        val b = path[path.size - 2]
        val c = path[path.size - 1]
        val orthoAB = (a[0] == b[0]) != (a[1] == b[1]) &&
            Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) == 1
        val orthoBC = (b[0] == c[0]) != (b[1] == c[1]) &&
            Math.abs(b[0] - c[0]) + Math.abs(b[1] - c[1]) == 1
        val diagAC = Math.abs(a[0] - c[0]) == 1 && Math.abs(a[1] - c[1]) == 1
        if (orthoAB && orthoBC && diagAC) {
            // Drop the corner pixel b: restore what it overwrote.
            val k = key(b[0], b[1])
            overwritten[k]?.let { cel.setPixel(b[0], b[1], it) }
            path.removeAt(path.size - 2)
        }
    }

    private fun stamp(cx: Int, cy: Int) {
        val r = brushSize / 2
        val x0 = cx - r
        val y0 = cy - r
        for (dy in 0 until brushSize) for (dx in 0 until brushSize) {
            val px = x0 + dx; val py = y0 + dy
            if (px < 0 || py < 0 || px >= doc.width || py >= doc.height) continue
            val k = key(px, py)
            if (k !in overwritten) overwritten[k] = cel.getPixel(px, py)
            cel.setPixel(px, py, paintValue)
        }
    }
}

object FillTool {
    /** Contiguous scanline flood fill on raw stored values (ARGB or index). */
    fun flood(doc: Document, cel: Cel, startX: Int, startY: Int, newValue: Int) {
        if (startX < 0 || startY < 0 || startX >= doc.width || startY >= doc.height) return
        val target = cel.getPixel(startX, startY)
        if (target == newValue) return
        val w = cel.width
        val stack = ArrayDeque<Int>()
        stack.addLast((startY - cel.y) * w + (startX - cel.x))
        val px = cel.pixels
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            if (px[idx] != target) continue
            var left = idx
            while (left % w > 0 && px[left - 1] == target) left--
            var right = idx
            while (right % w < w - 1 && px[right + 1] == target) right++
            for (i in left..right) {
                px[i] = newValue
                if (i - w >= 0 && px[i - w] == target) stack.addLast(i - w)
                if (i + w < px.size && px[i + w] == target) stack.addLast(i + w)
            }
        }
    }
}

object Eyedropper {
    /** Returns ARGB of the composited pixel value at (x, y) for the active cel,
     *  or the stored value (index) in indexed mode via [rawValue]. */
    fun rawValue(cel: Cel, x: Int, y: Int): Int = cel.getPixel(x, y)

    fun argb(doc: Document, cel: Cel, x: Int, y: Int): Int {
        val v = cel.getPixel(x, y)
        return if (doc.colorMode == ColorMode.INDEXED) doc.resolve(v) else v
    }
}
