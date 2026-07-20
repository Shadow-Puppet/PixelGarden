package dev.pixelsprite.model

/**
 * Flattens one frame of a document into an ARGB IntArray (bottom layer first).
 * Normal blend + layer opacity only; other blend modes are Advanced scope.
 */
object Compositor {

    fun composite(doc: Document, frame: Frame, out: IntArray) {
        java.util.Arrays.fill(out, 0)
        for (layer in doc.layers) {
            if (!layer.visible || layer.opacity == 0) continue
            val cel = doc.celAt(layer, frame) ?: continue
            blendCel(doc, cel, layer.opacity, out)
        }
    }

    private fun blendCel(doc: Document, cel: Cel, layerOpacity: Int, out: IntArray) {
        val dw = doc.width
        val x0 = cel.x.coerceAtLeast(0)
        val y0 = cel.y.coerceAtLeast(0)
        val x1 = (cel.x + cel.width).coerceAtMost(dw)
        val y1 = (cel.y + cel.height).coerceAtMost(doc.height)
        for (py in y0 until y1) {
            var src = (py - cel.y) * cel.width + (x0 - cel.x)
            var dst = py * dw + x0
            for (px in x0 until x1) {
                val argb = doc.resolve(cel.pixels[src])
                val sa = ((argb ushr 24) * layerOpacity) / 255
                if (sa > 0) out[dst] = blendOver(argb, sa, out[dst])
                src++; dst++
            }
        }
    }

    /** src-over with premultiplied math done inline. */
    private fun blendOver(src: Int, srcAlpha: Int, dst: Int): Int {
        val da = (dst ushr 24) and 0xFF
        if (srcAlpha == 255 || da == 0) return (srcAlpha shl 24) or (src and 0xFFFFFF)
        val inv = 255 - srcAlpha
        val outA = srcAlpha + da * inv / 255
        if (outA == 0) return 0
        fun ch(shift: Int): Int {
            val s = (src ushr shift) and 0xFF
            val d = (dst ushr shift) and 0xFF
            return (s * srcAlpha + d * da * inv / 255) / outA
        }
        return (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
