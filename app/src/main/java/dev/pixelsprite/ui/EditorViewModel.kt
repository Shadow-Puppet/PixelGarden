package dev.pixelsprite.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pixelsprite.io.NativeFormat
import dev.pixelsprite.io.PngExport
import dev.pixelsprite.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    var session = EditorSession(Document(width = 32, height = 32))
        private set

    /** Bumped on any session change so Compose chrome recomposes. */
    val tick = mutableLongStateOf(0L)

    init {
        session.addListener { tick.longValue++ }
    }

    fun newDocument(width: Int, height: Int) {
        session = EditorSession(Document(width = width, height = height))
        session.addListener { tick.longValue++ }
        tick.longValue++
    }

    fun saveTo(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
            NativeFormat.save(session.doc, it)
        }
    }

    fun loadFrom(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val doc = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            NativeFormat.load(it)
        } ?: return@launch
        session = EditorSession(doc)
        session.addListener { tick.longValue++ }
        tick.longValue++
    }

    fun exportPng(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
            PngExport.export(session.doc, session.activeFrame, it)
        }
    }
}
