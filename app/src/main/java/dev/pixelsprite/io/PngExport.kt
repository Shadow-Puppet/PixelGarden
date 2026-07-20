package dev.pixelsprite.io

import android.graphics.Bitmap
import dev.pixelsprite.model.Compositor
import dev.pixelsprite.model.Document
import dev.pixelsprite.model.Frame
import java.io.OutputStream

object PngExport {
    /** Exports one frame, flattened, as PNG. */
    fun export(doc: Document, frame: Frame, out: OutputStream) {
        val buf = IntArray(doc.width * doc.height)
        Compositor.composite(doc, frame, buf)
        val bmp = Bitmap.createBitmap(doc.width, doc.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(buf, 0, doc.width, 0, 0, doc.width, doc.height)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
}
