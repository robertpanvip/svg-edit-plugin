package com.example.svgeditor.plugin

import com.example.svgeditor.core.SvgEditorPanel
import com.example.svgeditor.core.SvgRenderer
import com.example.svgeditor.core.createEditorToolbar
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Right-hand side of the [SvgPreviewEditor]: an interactive design canvas bound to the same
 * document as the left-hand text editor, with bidirectional sync.
 *
 * It implements [FileEditor] (not just [JComponent]) because in this IntelliJ version
 * `TextEditorWithPreview` takes the preview as a `FileEditor` whose [getComponent] is the canvas.
 *
 * - text → canvas: a [DocumentListener] reloads the SVG into [SvgEditorPanel] whenever the source
 *   is edited in the text editor.
 * - canvas → text: [SvgEditorPanel.onEdit] (fired after a move / resize / rotate commit) writes the
 *   updated SVG back to the document via a [WriteCommandAction].
 *
 * A [suppressReload] guard prevents the canvas→text write from bouncing back into a reload.
 *
 * If the native renderer cannot be loaded (e.g. the plugin zip was built on another OS), the
 * canvas is replaced by [NativeLibGuidePanel] instead of crashing editor creation.
 */
class SvgPreviewPanel(
    private val project: Project,
    private val file: VirtualFile,
) : JPanel(BorderLayout()),
    FileEditor {
    private val userDataHolder = UserDataHolderBase()
    private val propertyChangeListeners = CopyOnWriteArrayList<PropertyChangeListener>()
    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)
    private var suppressReload = false

    /**
     * Null when the native renderer is unavailable (e.g. plugin zip built on another OS); in
     * that case the guide panel is shown instead of the canvas and editing is disabled until the
     * library is supplied.
     */
    private val panel: SvgEditorPanel? =
        SvgBridgeLoader.loadOrNull()?.let { renderer ->
            SvgEditorPanel(renderer, asyncRendering = true).apply {
                onEdit = { writeBack() }
            }
        }

    private val documentListener =
        object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (suppressReload) return
                panel?.loadSvg(event.document.text)
            }

            override fun beforeDocumentChange(event: DocumentEvent) {
                // no-op; reload happens after the change is applied
            }
        }

    init {
        val canvas = panel
        if (canvas != null) {
            val toolbar = createEditorToolbar(canvas, IdeaIconResolver)
            add(toolbar, BorderLayout.NORTH)
            add(canvas, BorderLayout.CENTER)

            document?.text?.let { canvas.loadSvg(it) }
        } else {
            add(NativeLibGuidePanel(SvgBridgeLoader.describeAttempts()), BorderLayout.CENTER)
        }
        document?.addDocumentListener(documentListener)
    }

    private fun writeBack() {
        val doc = document ?: return
        val canvas = panel ?: return
        val text = canvas.svgSource
        if (doc.text == text) return
        suppressReload = true
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                doc.setText(text)
            }
        } finally {
            suppressReload = false
        }
    }

    // ---- FileEditor implementation ----

    override fun getComponent(): JComponent = this

    override fun getPreferredFocusedComponent(): JComponent = this

    override fun getName(): String = "SvgEasy"

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeListeners.add(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChangeListeners.remove(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun getFile(): VirtualFile? = file

    override fun dispose() {
        document?.removeDocumentListener(documentListener)
        panel?.dispose()
    }

    override fun <T : Any?> getUserData(key: Key<T>): T? = userDataHolder.getUserData(key)

    override fun <T : Any?> putUserData(
        key: Key<T>,
        value: T?,
    ) = userDataHolder.putUserData(key, value)
}
