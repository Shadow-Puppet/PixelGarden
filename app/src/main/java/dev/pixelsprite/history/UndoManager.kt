package dev.pixelsprite.history

import dev.pixelsprite.model.Cel
import dev.pixelsprite.model.Document

interface Command {
    fun undo(doc: Document)
    fun redo(doc: Document)
    /** Approximate memory cost, used to cap history size. */
    val cost: Int
}

/**
 * Dirty-rect pixel diff for a single cel: stores before/after pixels of only
 * the changed region — never a full-canvas snapshot (per spec).
 */
class PixelDiffCommand(
    private val cel: Cel,
    private val rx: Int, private val ry: Int,       // rect origin in cel-local coords
    private val rw: Int, private val rh: Int,
    private val before: IntArray,                   // rw*rh
    private val after: IntArray,                    // rw*rh
) : Command {

    override val cost: Int = before.size * 8

    override fun undo(doc: Document) = apply(before)
    override fun redo(doc: Document) = apply(after)

    private fun apply(data: IntArray) {
        for (row in 0 until rh) {
            System.arraycopy(data, row * rw, cel.pixels, (ry + row) * cel.width + rx, rw)
        }
    }

    companion object {
        /** Build a diff by comparing a pre-edit copy of the cel against its current pixels. */
        fun fromSnapshot(cel: Cel, snapshot: IntArray): PixelDiffCommand? {
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
            var maxX = -1; var maxY = -1
            val w = cel.width
            for (i in cel.pixels.indices) {
                if (cel.pixels[i] != snapshot[i]) {
                    val x = i % w; val y = i / w
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < 0) return null // nothing changed
            val rw = maxX - minX + 1
            val rh = maxY - minY + 1
            val before = IntArray(rw * rh)
            val after = IntArray(rw * rh)
            for (row in 0 until rh) {
                val srcOff = (minY + row) * w + minX
                System.arraycopy(snapshot, srcOff, before, row * rw, rw)
                System.arraycopy(cel.pixels, srcOff, after, row * rw, rw)
            }
            return PixelDiffCommand(cel, minX, minY, rw, rh, before, after)
        }
    }
}

/** Structural changes (add/remove layer/frame, reorder…) captured as closures. */
class StructuralCommand(
    private val doUndo: (Document) -> Unit,
    private val doRedo: (Document) -> Unit,
) : Command {
    override val cost = 64
    override fun undo(doc: Document) = doUndo(doc)
    override fun redo(doc: Document) = doRedo(doc)
}

class UndoManager(private val maxCost: Int = 32 * 1024 * 1024) {
    private val undoStack = ArrayDeque<Command>()
    private val redoStack = ArrayDeque<Command>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun push(cmd: Command) {
        undoStack.addLast(cmd)
        redoStack.clear()
        trim()
    }

    fun undo(doc: Document) {
        val c = undoStack.removeLastOrNull() ?: return
        c.undo(doc)
        redoStack.addLast(c)
    }

    fun redo(doc: Document) {
        val c = redoStack.removeLastOrNull() ?: return
        c.redo(doc)
        undoStack.addLast(c)
    }

    fun clear() { undoStack.clear(); redoStack.clear() }

    private fun trim() {
        var total = undoStack.sumOf { it.cost.toLong() }
        while (total > maxCost && undoStack.size > 1) {
            total -= undoStack.removeFirst().cost
        }
    }
}
