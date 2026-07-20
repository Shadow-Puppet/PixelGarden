package dev.pixelsprite.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.pixelsprite.model.Cel
import dev.pixelsprite.model.ColorMode
import dev.pixelsprite.model.Document
import dev.pixelsprite.model.Frame
import dev.pixelsprite.model.Layer
import dev.pixelsprite.model.Palette
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Native ".pxs" format (per spec): a zip archive containing
 *   manifest.json               document structure
 *   cels/<n>.png                RGBA cels as PNG
 *   cels/<n>.idx                Indexed cels as raw bytes (one byte per pixel)
 *
 * Simple, debuggable, FOSS-friendly. Version field allows migration later.
 */
object NativeFormat {

    const val EXTENSION = "pxs"
    const val MIME = "application/zip"
    private const val VERSION = 1

    // ---- save ---------------------------------------------------------

    fun save(doc: Document, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val manifest = JSONObject().apply {
                put("version", VERSION)
                put("width", doc.width)
                put("height", doc.height)
                put("colorMode", doc.colorMode.name)
                put("palette", JSONArray().apply { doc.palette.colors.forEach { put(it) } })
                put("layers", JSONArray().apply {
                    doc.layers.forEach { l ->
                        put(JSONObject().apply {
                            put("id", l.id); put("name", l.name)
                            put("opacity", l.opacity); put("visible", l.visible)
                            put("locked", l.locked); put("blendMode", l.blendMode)
                        })
                    }
                })
                put("frames", JSONArray().apply {
                    doc.frames.forEach { f ->
                        put(JSONObject().apply { put("id", f.id); put("durationMs", f.durationMs) })
                    }
                })
            }

            var celFileIndex = 0
            val celsJson = JSONArray()
            for ((key, cel) in doc.allCels()) {
                val (layerId, frameId) = key
                val name = "cels/$celFileIndex." +
                    if (doc.colorMode == ColorMode.INDEXED) "idx" else "png"
                celsJson.put(JSONObject().apply {
                    put("layerId", layerId); put("frameId", frameId)
                    put("x", cel.x); put("y", cel.y)
                    put("w", cel.width); put("h", cel.height)
                    put("file", name)
                })
                zip.putNextEntry(ZipEntry(name))
                zip.write(encodeCel(doc, cel))
                zip.closeEntry()
                celFileIndex++
            }
            manifest.put("cels", celsJson)

            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun encodeCel(doc: Document, cel: Cel): ByteArray =
        if (doc.colorMode == ColorMode.INDEXED) {
            ByteArray(cel.pixels.size) { i -> cel.pixels[i].toByte() }
        } else {
            val bmp = Bitmap.createBitmap(cel.width, cel.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(cel.pixels, 0, cel.width, 0, 0, cel.width, cel.height)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        }

    // ---- load ---------------------------------------------------------

    fun load(input: InputStream): Document {
        var manifestBytes: ByteArray? = null
        val celFiles = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val bytes = zip.readBytes()
                if (entry.name == "manifest.json") manifestBytes = bytes
                else if (entry.name.startsWith("cels/")) celFiles[entry.name] = bytes
                entry = zip.nextEntry
            }
        }
        val m = JSONObject(String(manifestBytes ?: error("manifest.json missing"), Charsets.UTF_8))

        val paletteArr = m.getJSONArray("palette")
        val palette = Palette(IntArray(paletteArr.length()) { paletteArr.getInt(it) })
        val doc = Document(
            width = m.getInt("width"),
            height = m.getInt("height"),
            colorMode = ColorMode.valueOf(m.getString("colorMode")),
            palette = palette,
        )

        doc.layers.clear()
        val layersArr = m.getJSONArray("layers")
        val layerById = HashMap<Long, Layer>()
        for (i in 0 until layersArr.length()) {
            val lo = layersArr.getJSONObject(i)
            val layer = Layer(
                id = lo.getLong("id"),
                name = lo.getString("name"),
                opacity = lo.optInt("opacity", 255),
                visible = lo.optBoolean("visible", true),
                locked = lo.optBoolean("locked", false),
                blendMode = lo.optString("blendMode", "normal"),
            )
            doc.layers.add(layer)
            layerById[layer.id] = layer
        }

        doc.frames.clear()
        val framesArr = m.getJSONArray("frames")
        val frameById = HashMap<Long, Frame>()
        for (i in 0 until framesArr.length()) {
            val fo = framesArr.getJSONObject(i)
            val frame = Frame(id = fo.getLong("id"), durationMs = fo.optInt("durationMs", 100))
            doc.frames.add(frame)
            frameById[frame.id] = frame
        }

        val celsArr = m.getJSONArray("cels")
        for (i in 0 until celsArr.length()) {
            val co = celsArr.getJSONObject(i)
            val layer = layerById[co.getLong("layerId")] ?: continue
            val frame = frameById[co.getLong("frameId")] ?: continue
            val w = co.getInt("w"); val h = co.getInt("h")
            val bytes = celFiles[co.getString("file")] ?: continue
            val pixels = decodeCel(doc, bytes, w, h)
            doc.putCel(layer, frame, Cel(
                x = co.getInt("x"), y = co.getInt("y"),
                width = w, height = h, pixels = pixels,
            ))
        }
        return doc
    }

    private fun decodeCel(doc: Document, bytes: ByteArray, w: Int, h: Int): IntArray =
        if (doc.colorMode == ColorMode.INDEXED) {
            IntArray(w * h) { i -> bytes[i].toInt() and 0xFF }
        } else {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        }
}
