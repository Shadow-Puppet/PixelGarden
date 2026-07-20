package dev.pixelsprite.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pixelsprite.canvas.PixelCanvasView
import dev.pixelsprite.model.ColorMode
import dev.pixelsprite.tools.ToolKind

/**
 * UI chrome is Compose (fine per spec); the canvas itself is the custom
 * PixelCanvasView hosted via AndroidView. Panels are compact rows suited to a
 * phone; slide-out/collapsible panel layout is a QoL follow-up.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EditorScreen()
            }
        }
    }
}

@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    // Reading tick subscribes this scope to session changes.
    @Suppress("UNUSED_VARIABLE") val tick = vm.tick.longValue
    val s = vm.session

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? -> uri?.let(vm::saveTo) }
    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(vm::loadFrom) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri: Uri? -> uri?.let(vm::exportPng) }

    var showColorPicker by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        TopBar(
            s = s,
            onNew = { showNewDialog = true },
            onSave = { saveLauncher.launch("sprite.pxs") },
            onLoad = { loadLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            onExport = { exportLauncher.launch("sprite.png") },
        )
        ToolBar(s)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx -> PixelCanvasView(ctx) },
                update = { view -> if (view.session !== s) view.session = s },
                modifier = Modifier.fillMaxSize(),
            )
        }
        SwatchBar(s, onOpenPicker = { showColorPicker = true })
        LayerBar(s)
        TimelineStrip(s)
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initial = s.primaryArgb,
            onPick = { argb ->
                if (s.doc.colorMode == ColorMode.INDEXED) {
                    // In indexed mode the picker edits the active palette entry —
                    // this live-remaps every pixel using that index.
                    s.doc.palette.set(s.primaryIndex, argb)
                    s.notifyChanged()
                } else {
                    s.primaryArgb = argb
                }
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false },
        )
    }
    if (showNewDialog) {
        NewDocumentDialog(
            onCreate = { w, h -> vm.newDocument(w, h); showNewDialog = false },
            onDismiss = { showNewDialog = false },
        )
    }
}

// ---- top bar -----------------------------------------------------------

@Composable
private fun TopBar(
    s: EditorSession,
    onNew: () -> Unit, onSave: () -> Unit, onLoad: () -> Unit, onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton("New", onNew)
        BarButton("Open", onLoad)
        BarButton("Save", onSave)
        BarButton("PNG", onExport)
        Spacer(Modifier.weight(1f))
        BarButton(if (s.doc.colorMode == ColorMode.RGBA) "RGBA" else "IDX") {
            s.setColorMode(
                if (s.doc.colorMode == ColorMode.RGBA) ColorMode.INDEXED else ColorMode.RGBA
            )
        }
        BarButton("↶", enabled = s.undo.canUndo) { s.doUndo() }
        BarButton("↷", enabled = s.undo.canRedo) { s.doRedo() }
    }
}

@Composable
private fun BarButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        label,
        color = if (enabled) Color(0xFFDDDDDD) else Color(0xFF555555),
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

// ---- tools -------------------------------------------------------------

@Composable
private fun ToolBar(s: EditorSession) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF161616)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton("✏", s.tool == ToolKind.PENCIL) { s.tool = ToolKind.PENCIL }
        ToolButton("◻", s.tool == ToolKind.ERASER) { s.tool = ToolKind.ERASER }
        ToolButton("◍", s.tool == ToolKind.FILL) { s.tool = ToolKind.FILL }
        ToolButton("💧", s.tool == ToolKind.EYEDROPPER) { s.tool = ToolKind.EYEDROPPER }
        Text("  ${s.brushSize}px", color = Color(0xFF999999), fontSize = 12.sp)
        Slider(
            value = s.brushSize.toFloat(),
            onValueChange = { s.brushSize = it.toInt() },
            valueRange = 1f..16f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("PP", color = Color(0xFF999999), fontSize = 12.sp)
        Checkbox(checked = s.pixelPerfect, onCheckedChange = { s.pixelPerfect = it })
    }
}

@Composable
private fun ToolButton(glyph: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(2.dp)
            .size(44.dp)
            .background(if (selected) Color(0xFF2E4A66) else Color(0xFF222222))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 18.sp) }
}

// ---- swatches ----------------------------------------------------------

@Composable
private fun SwatchBar(s: EditorSession, onOpenPicker: () -> Unit) {
    val indexed = s.doc.colorMode == ColorMode.INDEXED
    LazyRow(
        Modifier.fillMaxWidth().background(Color(0xFF161616)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            // Current color / open picker
            Box(
                Modifier
                    .size(40.dp)
                    .border(2.dp, Color.White)
                    .background(Color(s.primaryArgb))
                    .clickable(onClick = onOpenPicker)
            )
        }
        itemsIndexed(s.doc.palette.colors.toList()) { i, argb ->
            if (indexed || i > 0) { // hide the reserved transparent entry in RGBA mode
                val selected = if (indexed) s.primaryIndex == i else s.primaryArgb == argb
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(32.dp)
                        .border(if (selected) 2.dp else 1.dp,
                            if (selected) Color.White else Color(0xFF444444))
                        .background(if (i == 0) Color(0xFF303030) else Color(argb))
                        .clickable {
                            if (indexed) s.primaryIndex = i
                            else s.primaryArgb = argb
                        }
                )
            }
        }
        item {
            BarButton("+") {
                val idx = s.doc.palette.add(s.primaryArgb)
                if (idx >= 0 && indexed) s.primaryIndex = idx
                s.notifyChanged()
            }
        }
    }
}

// ---- layers ------------------------------------------------------------

@Composable
private fun LayerBar(s: EditorSession) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF141414)).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton("+L") { s.addLayer() }
        BarButton("-L") { s.deleteActiveLayer() }
        BarButton("↑") {
            val i = s.activeLayerIndex
            if (i < s.doc.layers.lastIndex) s.moveLayer(i, i + 1)
        }
        BarButton("↓") {
            val i = s.activeLayerIndex
            if (i > 0) s.moveLayer(i, i - 1)
        }
        LazyRow(Modifier.weight(1f)) {
            // Top-most layer first, like a layer list.
            items(s.doc.layers.indices.reversed().toList()) { i ->
                val layer = s.doc.layers[i]
                val active = i == s.activeLayerIndex
                Row(
                    Modifier
                        .padding(2.dp)
                        .background(if (active) Color(0xFF2E4A66) else Color(0xFF222222))
                        .clickable { s.activeLayerIndex = i }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (layer.visible) "👁 " else "◌ "),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            layer.visible = !layer.visible; s.notifyChanged()
                        },
                    )
                    Text(
                        (if (layer.locked) "🔒 " else ""), fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            layer.locked = !layer.locked; s.notifyChanged()
                        },
                    )
                    Text(layer.name, color = Color(0xFFCCCCCC), fontSize = 12.sp)
                }
            }
        }
        // Active layer opacity
        Slider(
            value = s.activeLayer.opacity.toFloat(),
            onValueChange = { s.activeLayer.opacity = it.toInt(); s.notifyChanged() },
            valueRange = 0f..255f,
            modifier = Modifier.weight(0.6f).padding(horizontal = 4.dp),
        )
    }
}

// ---- timeline ----------------------------------------------------------

@Composable
private fun TimelineStrip(s: EditorSession) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF121212)).padding(2.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton("+F") { s.addFrame() }
        BarButton("-F") { s.deleteActiveFrame() }
        LazyRow(Modifier.weight(1f)) {
            itemsIndexed(s.doc.frames.toList()) { i, frame ->
                val active = i == s.activeFrameIndex
                Column(
                    Modifier
                        .padding(2.dp)
                        .size(width = 48.dp, height = 44.dp)
                        .background(if (active) Color(0xFF2E4A66) else Color(0xFF222222))
                        .clickable { s.activeFrameIndex = i },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("${i + 1}", color = Color(0xFFCCCCCC), fontSize = 13.sp)
                    Text("${frame.durationMs}ms", color = Color(0xFF777777), fontSize = 9.sp)
                }
            }
        }
    }
}

// ---- dialogs -----------------------------------------------------------

@Composable
private fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var r by remember { mutableStateOf(((initial shr 16) and 0xFF).toFloat()) }
    var g by remember { mutableStateOf(((initial shr 8) and 0xFF).toFloat()) }
    var b by remember { mutableStateOf((initial and 0xFF).toFloat()) }
    val argb = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(argb) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Color") },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color(argb)))
                ChannelSlider("R", r) { r = it }
                ChannelSlider("G", g) { g = it }
                ChannelSlider("B", b) { b = it }
                // HSV picker + hex entry: QoL refinement over this RGB baseline.
            }
        },
    )
}

@Composable
private fun ChannelSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..255f,
            modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NewDocumentDialog(onCreate: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var size by remember { mutableStateOf(32f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onCreate(size.toInt(), size.toInt()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New document") },
        text = {
            Column {
                Text("${size.toInt()} × ${size.toInt()} px")
                Slider(value = size, onValueChange = { size = it }, valueRange = 8f..256f)
                // Non-square sizes + presets: straightforward follow-up.
            }
        },
    )
}
