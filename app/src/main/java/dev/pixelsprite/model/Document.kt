package dev.pixelsprite.model

import java.util.concurrent.atomic.AtomicLong

/**
 * Data model mirroring Aseprite's:
 * Document -> Layers x Frames, with sparse Cels at intersections.
 *
 * Pixels are stored per-cel:
 *  - RGBA mode:    IntArray of ARGB ints (Android Bitmap order).
 *  - INDEXED mode: IntArray of palette indices (0..255). Index 0 is reserved
 *    as transparent. Resolution to color happens in the compositor, so
 *    editing a palette entry live-remaps everything using that index.
 */

enum class ColorMode { RGBA, INDEXED }

private val idGen = AtomicLong(1)
fun nextId(): Long = idGen.getAndIncrement()

class Palette(initial: IntArray = defaultPalette()) {
    /** ARGB entries. In INDEXED mode entry 0 is the transparent index. */
    var colors: IntArray = initial
        private set

    val size: Int get() = colors.size

    operator fun get(i: Int): Int = if (i in colors.indices) colors[i] else 0

    fun set(i: Int, argb: Int) {
        if (i in colors.indices) colors[i] = argb
    }

    fun add(argb: Int): Int {
        if (colors.size >= 256) return -1
        colors += argb
        return colors.size - 1
    }

    fun replaceAll(newColors: IntArray) {
        colors = newColors.copyOf(newColors.size.coerceAtMost(256))
    }

    /** Nearest entry by RGB distance; skips the transparent index in indexed use. */
    fun nearestIndex(argb: Int, skipTransparent: Boolean): Int {
        var best = if (skipTransparent) 1 else 0
        var bestD = Long.MAX_VALUE
        val start = if (skipTransparent) 1 else 0
        for (i in start until colors.size) {
            val c = colors[i]
            val dr = ((argb ushr 16) and 0xFF) - ((c ushr 16) and 0xFF)
            val dg = ((argb ushr 8) and 0xFF) - ((c ushr 8) and 0xFF)
            val db = (argb and 0xFF) - (c and 0xFF)
            val d = (dr * dr + dg * dg + db * db).toLong()
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    companion object {
        fun defaultPalette(): IntArray = intArrayOf(
            0x00000000,             // 0: transparent (reserved)
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            0xFF9D9D9D.toInt(), 0xFFBE2633.toInt(), 0xFFE06F8B.toInt(),
            0xFF493C2B.toInt(), 0xFFA46422.toInt(), 0xFFEB8931.toInt(),
            0xFFF7E26B.toInt(), 0xFF2F484E.toInt(), 0xFF44891A.toInt(),
            0xFFA3CE27.toInt(), 0xFF1B2632.toInt(), 0xFF005784.toInt(),
            0xFF31A2F2.toInt(), 0xFFB2DCEF.toInt(),
        )
    }
}

class Layer(
    val id: Long = nextId(),
    var name: String,
    var opacity: Int = 255,          // 0..255
    var visible: Boolean = true,
    var locked: Boolean = false,
    // Blend modes beyond Normal are "Advanced" scope; field exists so the
    // format & compositor won't need migration later.
    var blendMode: String = "normal",
)

class Frame(
    val id: Long = nextId(),
    var durationMs: Int = 100,
)

/**
 * A Cel is the pixel content at one layer x frame intersection.
 * Sparse: many intersections have no cel. Bitmaps are small and carry an
 * (x, y) offset into the document canvas.
 */
class Cel(
    val id: Long = nextId(),
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    var pixels: IntArray, // ARGB or palette indices, see ColorMode
) {
    fun contains(px: Int, py: Int): Boolean =
        px >= x && py >= y && px < x + width && py < y + height

    fun getPixel(px: Int, py: Int): Int =
        if (contains(px, py)) pixels[(py - y) * width + (px - x)] else 0

    fun setPixel(px: Int, py: Int, v: Int) {
        if (contains(px, py)) pixels[(py - y) * width + (px - x)] = v
    }

    companion object {
        fun blank(x: Int, y: Int, w: Int, h: Int) =
            Cel(x = x, y = y, width = w, height = h, pixels = IntArray(w * h))
    }
}

class Document(
    var width: Int,
    var height: Int,
    var colorMode: ColorMode = ColorMode.RGBA,
    val palette: Palette = Palette(),
) {
    val layers = mutableListOf(Layer(name = "Layer 1"))
    val frames = mutableListOf(Frame())

    /** Sparse cel storage keyed by (layerId, frameId). */
    private val cels = HashMap<Pair<Long, Long>, Cel>()

    fun celAt(layer: Layer, frame: Frame): Cel? = cels[layer.id to frame.id]

    /** Cel for editing; created lazily (full-canvas for v1 simplicity —
     *  shrinking to dirty bounds is a later optimization, the model already
     *  supports offsets/sizes). */
    fun celForEditing(layer: Layer, frame: Frame): Cel =
        cels.getOrPut(layer.id to frame.id) { Cel.blank(0, 0, width, height) }

    fun putCel(layer: Layer, frame: Frame, cel: Cel) { cels[layer.id to frame.id] = cel }
    fun removeCel(layer: Layer, frame: Frame) { cels.remove(layer.id to frame.id) }

    fun allCels(): Map<Pair<Long, Long>, Cel> = cels

    // ---- structure ops ------------------------------------------------

    fun addLayer(name: String = "Layer ${layers.size + 1}"): Layer =
        Layer(name = name).also { layers.add(it) }

    fun deleteLayer(layer: Layer) {
        if (layers.size <= 1) return
        layers.remove(layer)
        frames.forEach { f -> cels.remove(layer.id to f.id) }
    }

    fun moveLayer(from: Int, to: Int) {
        if (from !in layers.indices || to !in layers.indices) return
        layers.add(to, layers.removeAt(from))
    }

    fun addFrame(afterIndex: Int = frames.size - 1): Frame {
        val f = Frame(durationMs = frames.getOrNull(afterIndex)?.durationMs ?: 100)
        frames.add((afterIndex + 1).coerceIn(0, frames.size), f)
        return f
    }

    fun deleteFrame(frame: Frame) {
        if (frames.size <= 1) return
        frames.remove(frame)
        layers.forEach { l -> cels.remove(l.id to frame.id) }
    }

    fun moveFrame(from: Int, to: Int) {
        if (from !in frames.indices || to !in frames.indices) return
        frames.add(to, frames.removeAt(from))
    }

    // ---- color mode conversion ---------------------------------------

    /** Resolve a stored pixel value to ARGB for display. */
    fun resolve(value: Int): Int = when (colorMode) {
        ColorMode.RGBA -> value
        ColorMode.INDEXED -> if (value == 0) 0 else palette[value]
    }

    fun convertTo(mode: ColorMode) {
        if (mode == colorMode) return
        when (mode) {
            ColorMode.RGBA -> cels.values.forEach { cel ->
                for (i in cel.pixels.indices) cel.pixels[i] = resolve(cel.pixels[i])
            }
            ColorMode.INDEXED -> cels.values.forEach { cel ->
                for (i in cel.pixels.indices) {
                    val argb = cel.pixels[i]
                    cel.pixels[i] =
                        if ((argb ushr 24) == 0) 0
                        else palette.nearestIndex(argb, skipTransparent = true)
                }
            }
        }
        colorMode = mode
    }
}
