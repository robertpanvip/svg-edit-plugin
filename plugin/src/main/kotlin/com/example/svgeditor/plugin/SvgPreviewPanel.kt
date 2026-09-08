package com.example.svgeditor.plugin

import com.example.svgeditor.core.SidecarClient
import com.example.svgeditor.core.SvgEditorPanel
import com.example.svgeditor.core.SvgRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer
import kotlin.math.roundToInt

private val LOG = Logger.getInstance("SvgEasy")

/**
 * Right-hand side of the [SvgPreviewEditor]: an interactive design canvas bound to the same
 * document as the left-hand text editor, with bidirectional sync.
 *
 * It extends [UserDataHolderBase] and implements [FileEditor] with [Disposable]; the Swing
 * container is the internal [root] component returned by [getComponent], keeping the editor
 * lifecycle separate from the widget hierarchy (the same shape the platform's own preview
 * editors use, since `TextEditorWithPreview` takes the preview as a `FileEditor`).
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
) : UserDataHolderBase(),
    FileEditor,
    Disposable {
    private val root = JPanel(BorderLayout())
    private val propertyChangeListeners = CopyOnWriteArrayList<PropertyChangeListener>()
    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)
    private var suppressReload = false

    /** Owned by this editor: closed in [dispose] (the panel only borrows it). */
    private var ownedSidecar: SidecarClient? = null

    /**
     * Null when the native renderer is unavailable (e.g. plugin zip built on another OS); in
     * that case the guide panel is shown instead of the canvas and editing is disabled until the
     * library is supplied.
     */
    private val panel: SvgEditorPanel? =
        SvgBridgeLoader.loadOrNull()?.let { renderer ->
            val sidecarClient = resolveSidecarClient()
            SvgEditorPanel(renderer, asyncRendering = true, sidecar = sidecarClient).apply {
                onEdit = { writeBack() }
            }
        }

    /**
     * Sidecar client for the document bound to this panel, or null when the executable is not
     * bundled (the panel then runs the legacy in-process pipeline). Created once per editor.
     */
    private fun resolveSidecarClient(): SidecarClient? {
        val command = SidecarLoader.resolveOrNull() ?: return null
        return SidecarClient(listOf(command)).also { ownedSidecar = it }
    }

    private val toolbar: JComponent? = panel?.let { SvgEasyToolbar.forPanel(it) }

    /**
     * Bottom status strip mirroring the built-in image viewer's size display: shows the SVG's
     * pixel dimensions (`W × H px`) and the current zoom. Refreshed on every status emission from
     * the canvas ([SvgEditorPanel.onStatus]) and on load.
     */
    private val infoValue =
        JLabel(" ").apply {
            horizontalAlignment = SwingConstants.RIGHT
        }

    private val infoBar: JPanel =
        JPanel(BorderLayout()).apply {
            add(infoValue, BorderLayout.EAST)
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                    BorderFactory.createEmptyBorder(3, 8, 3, 8),
                )
        }

    private fun refreshInfo() {
        val canvas = panel ?: return
        val w = canvas.layout.width
        val h = canvas.layout.height
        val dim = if (w > 0 && h > 0) "${w.roundToInt()} × ${h.roundToInt()} px" else "— × — px"
        infoValue.text =
            if (w > 0 && h > 0) "$dim · Zoom ${canvas.getZoomPercent()}%" else dim
    }

    private val documentListener =
        object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (suppressReload) return
                // Debounce: reload the canvas 300ms after the user stops typing so continuous
                // editing does not re-parse/re-render on every keystroke. Text edits from the
                // canvas write-back are suppressed above and never reach this path.
                reloadDebounce.restart()
            }

            override fun beforeDocumentChange(event: DocumentEvent) {
                // no-op; reload happens after the change is applied
            }
        }

    /**
     * Text -> canvas sync, debounced 300ms (the spec's "text edits mirror back to the canvas"
     * direction): the heavy parse/render runs once the user pauses, never mid-keystroke.
     */
    private val reloadDebounce =
        Timer(300) {
            val doc = document ?: return@Timer
            if (suppressReload) return@Timer
            loadSafely(doc.text)
        }.apply { isRepeats = false }

    init {
        if (panel != null) {
            // Give the split panes a meaningful initial extent, so the preview isn't squeezed to
            // zero width inside TextEditorWithPreview's splitter (the tool-window path uses a
            // BorderLayout holder and always has room, which is why only the tab looked blank).
            root.preferredSize = Dimension(480, 360)
            root.minimumSize = Dimension(200, 120)
            panel.onStatus = { refreshInfo() }
            panel.onRenderError = { showParseError(it) }
            // document can be null for exotic VFS states; fall back to the raw file bytes.
            fileText()?.let { loadSafely(it) } ?: showCanvas()
        } else {
            root.add(NativeLibGuidePanel(SvgBridgeLoader.describeAttempts()), BorderLayout.CENTER)
        }
        document?.addDocumentListener(documentListener)
        root.addComponentListener(
            object : ComponentAdapter() {
                override fun componentShown(e: ComponentEvent) {
                    LOG.info(
                        "preview: shown panel=${root.width}x${root.height} canvas=${panel?.width}x${panel?.height} " +
                            "inner=${panel?.debugCanvas()?.width}x${panel?.debugCanvas()?.height} " +
                            "svg=${panel?.layout?.width}x${panel?.layout?.height}",
                    )
                }
            },
        )
        Timer(
            2000,
        ) {
            LOG.info(
                "preview: probe showing=${root.isShowing} size=${root.width}x${root.height} " +
                    "canvas=${panel?.width}x${panel?.height} svg=${panel?.layout?.width}x${panel?.layout?.height}",
            )
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** Document text when available, else the file bytes; null when neither is readable. */
    private fun fileText(): String? =
        document?.text
            ?: runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()

    /** [SvgEditorPanel.loadSvg] that never throws: parse failures become a visible notice. */
    private fun loadSafely(text: String) {
        val canvas = panel ?: return
        try {
            canvas.loadSvg(text)
            showCanvas()
        } catch (t: Throwable) {
            LOG.warn("preview: loadSvg failed", t)
            showParseError(t)
        }
    }

    private fun showCanvas() {
        val canvas = panel ?: return
        if (canvas.parent === root && (toolbar == null || toolbar.parent === root)) return
        root.removeAll()
        toolbar?.let { root.add(it, BorderLayout.NORTH) }
        root.add(canvas, BorderLayout.CENTER)
        root.add(infoBar, BorderLayout.SOUTH)
        refreshInfo()
        root.revalidate()
        root.repaint()
    }

    private fun showParseError(t: Throwable) {
        val detail = escapeHtml((t.message ?: t.javaClass.simpleName)).take(200)
        root.removeAll()
        root.add(
            JLabel(
                "<html><b>This SVG could not be rendered by SvgEasy</b><br>" +
                    "<span style=\"color:#888888\">$detail</span></html>",
            ).apply {
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            },
            BorderLayout.CENTER,
        )
        root.revalidate()
        root.repaint()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = root

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
        reloadDebounce.stop()
        panel?.dispose()
        ownedSidecar?.close()
        ownedSidecar = null
    }
}
