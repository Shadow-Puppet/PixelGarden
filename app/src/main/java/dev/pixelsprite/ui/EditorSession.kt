package dev.pixelsprite.ui

import dev.pixelsprite.history.PixelDiffCommand
import dev.pixelsprite.history.StructuralCommand
import dev.pixelsprite.history.UndoManager
import dev.pixelsprite.model.Cel
import dev.pixelsprite.model.ColorMode
import dev.pixelsprite.model.Document
import dev.pixelsprite.model.Frame
import dev.pixelsprite.model.Layer
import dev.pixelsprite.tools.Eyedropper
import dev.pixelsprite.tools.FillTool
import dev.pixelsprite.tools.InputPoint
import dev.pixelsprite.tools.StrokeEngine
import dev.pixelsprite.tools.ToolKind

/**
 * Owns the document, undo history and active tool state. All edits funnel
 * through here so pixel changes are always wrapped in dirty-rect undo diffs.
 * UI layers (canvas view + Compose chrome) observe via [onChanged].
 */
class EditorSession(var doc: Document) {

    val undo = UndoManager()

    var activeLayerIndex = 0
        set(v) { field = v.coerceIn(0, doc.layers.lastIndex); notifyChanged() }
    var activeFrameIndex = 0
        set(v) { field = v.coerceIn(0, doc.frames.lastIndex); notifyChanged() }

    val activeLayer: Layer get() = doc.layers[activeLayerIndex.coerceIn(0, doc.layers.lastIndex)]
    val activeFrame: Frame get() = doc.frames[activeFrameIndex.coerceIn(0, doc.frames.lastIndex)]

    var tool: ToolKind = ToolKind.PENCIL
        set(v) { field = v; notifyChanged() }
    var brushSize: Int = 1
        set(v) { field = v.coerceIn(1, 64); notifyChanged() }
    var pixelPerfect: Boolean = true
        set(v) { field = v; notifyChanged() }
    var palmRejection: Boolean = true

    /** Primary color as ARGB (RGBA mode) and as palette index (INDEXED mode). */
    var primaryArgb: Int = 0xFF000000.toInt()
        set(v) { field = v; notifyChanged() }
    var primaryIndex: Int = 1
        set(v) { field = v; notifyChanged() }

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun notifyChanged() { listeners.forEach { it() } }

    private fun paintValue(): Int =
        if (doc.colorMode == ColorMode.INDEXED) primaryIndex else primaryArgb

    private fun eraseValue(): Int = 0 // transparent index 0 / ARGB 0

    // ---- stroke lifecycle (pencil / eraser) ---------------------------

    private var engine: StrokeEngine? = null
    private var strokeCel: Cel? = null
    private var strokeSnapshot: IntArray? = null

    fun canEdit(): Boolean = !activeLayer.locked && activeLayer.visible

    fun strokeBegin(p: InputPoint) {
        if (!canEdit()) return
        when (tool) {
            ToolKind.PENCIL, ToolKind.ERASER -> {
                val cel = doc.celForEditing(activeLayer, activeFrame)
                strokeCel = cel
                strokeSnapshot = cel.pixels.copyOf()
                engine = StrokeEngine(doc, cel, brushSize, pixelPerfect).also {
                    it.paintValue = if (tool == ToolKind.ERASER) eraseValue() else paintValue()
                    it.begin(p)
                }
                notifyChanged()
            }
            ToolKind.FILL -> {
                val cel = doc.celForEditing(activeLayer, activeFrame)
                val snap = cel.pixels.copyOf()
                FillTool.flood(doc, cel, p.x, p.y, paintValue())
                PixelDiffCommand.fromSnapshot(cel, snap)?.let(undo::push)
                notifyChanged()
            }
            ToolKind.EYEDROPPER -> {
                val cel = doc.celAt(activeLayer, activeFrame) ?: return
                if (doc.colorMode == ColorMode.INDEXED) {
                    primaryIndex = Eyedropper.rawValue(cel, p.x, p.y)
                } else {
                    val c = Eyedropper.argb(doc, cel, p.x, p.y)
                    if ((c ushr 24) != 0) primaryArgb = c
                }
            }
        }
    }

    fun strokeMove(p: InputPoint) {
        engine?.move(p)
        if (engine != null) notifyChanged()
    }

    fun strokeEnd() {
        val cel = strokeCel
        val snap = strokeSnapshot
        engine?.end()
        if (cel != null && snap != null) {
            PixelDiffCommand.fromSnapshot(cel, snap)?.let(undo::push)
        }
        engine = null; strokeCel = null; strokeSnapshot = null
        notifyChanged()
    }

    /** Two-finger gesture started mid-stroke: revert the partial stroke. */
    fun strokeCancel() {
        val cel = strokeCel
        val snap = strokeSnapshot
        if (cel != null && snap != null) System.arraycopy(snap, 0, cel.pixels, 0, snap.size)
        engine = null; strokeCel = null; strokeSnapshot = null
        notifyChanged()
    }

    fun doUndo() { undo.undo(doc); notifyChanged() }
    fun doRedo() { undo.redo(doc); notifyChanged() }

    // ---- structural ops with undo -------------------------------------

    fun addLayer() {
        val layer = doc.addLayer()
        val index = doc.layers.indexOf(layer)
        undo.push(StructuralCommand(
            doUndo = { it.layers.remove(layer) },
            doRedo = { it.layers.add(index.coerceAtMost(it.layers.size), layer) },
        ))
        activeLayerIndex = index
    }

    fun deleteActiveLayer() {
        if (doc.layers.size <= 1) return
        val layer = activeLayer
        val index = activeLayerIndex
        val removedCels = doc.frames.mapNotNull { f -> doc.celAt(layer, f)?.let { f to it } }
        doc.deleteLayer(layer)
        undo.push(StructuralCommand(
            doUndo = { d ->
                d.layers.add(index.coerceAtMost(d.layers.size), layer)
                removedCels.forEach { (f, c) -> d.putCel(layer, f, c) }
            },
            doRedo = { it.deleteLayer(layer) },
        ))
        activeLayerIndex = (index - 1).coerceAtLeast(0)
    }

    fun addFrame() {
        val frame = doc.addFrame(activeFrameIndex)
        val index = doc.frames.indexOf(frame)
        undo.push(StructuralCommand(
            doUndo = { it.frames.remove(frame) },
            doRedo = { it.frames.add(index.coerceAtMost(it.frames.size), frame) },
        ))
        activeFrameIndex = index
    }

    fun deleteActiveFrame() {
        if (doc.frames.size <= 1) return
        val frame = activeFrame
        val index = activeFrameIndex
        val removedCels = doc.layers.mapNotNull { l -> doc.celAt(l, frame)?.let { l to it } }
        doc.deleteFrame(frame)
        undo.push(StructuralCommand(
            doUndo = { d ->
                d.frames.add(index.coerceAtMost(d.frames.size), frame)
                removedCels.forEach { (l, c) -> d.putCel(l, frame, c) }
            },
            doRedo = { it.deleteFrame(frame) },
        ))
        activeFrameIndex = (index - 1).coerceAtLeast(0)
    }

    fun moveLayer(from: Int, to: Int) {
        doc.moveLayer(from, to)
        undo.push(StructuralCommand(
            doUndo = { it.moveLayer(to, from) },
            doRedo = { it.moveLayer(from, to) },
        ))
        if (activeLayerIndex == from) activeLayerIndex = to
        notifyChanged()
    }

    fun setColorMode(mode: ColorMode) {
        // Conversion is lossy (RGBA -> Indexed); history is cleared rather than
        // attempting to diff a whole-document transform.
        doc.convertTo(mode)
        undo.clear()
        notifyChanged()
    }
}
