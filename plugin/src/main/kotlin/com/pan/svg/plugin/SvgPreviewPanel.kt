package com.pan.svg.plugin

import com.pan.svg.core.SidecarClient
import com.pan.svg.core.SvgEditorPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.openapi.util.Computable
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
import javax.swing.SwingUtilities
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
 * Construction is cheap and never blocks the EDT: the constructor shows a brief "loading"
 * placeholder and hands the expensive work (locating the native renderer, extracting JNA's
 * jnidispatch, building the canvas, parsing the document) to a pooled thread
 * ([startBackgroundInit]). The finished canvas is assembled onto [root] on the EDT. Text edits
 * made while the renderer is still loading are picked up by the debounced reload as usual, and a
 * load performed before the canvas existed is never lost — the background init parses the
 * current document text when it runs.
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

    /** Set when the editor is closed; a still-running background init must then discard its work. */
    @Volatile private var disposed = false

    /**
     * The canvas, null until the background init finished (or when the native renderer is
     * unavailable — e.g. plugin zip built on another OS — in which case the guide panel is shown
     * instead and editing is disabled until the library is supplied). Filled on the EDT.
     */
    private var panel: SvgEditorPanel? = null

    /** Built on the EDT once [panel] exists. */
    private var toolbar: JComponent? = null

    /** Owned by this editor: closed in [dispose] (the panel only borrows it). */
    private var ownedSidecar: SidecarClient? = null

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
     * direction): the heavy parse/render runs once the user pauses, never mid-keystroke. A tick
     * that fires while the canvas is still being built is a no-op ([panel] is null then); the
     * background init parses the current document text anyway.
     */
    private val reloadDebounce =
        Timer(300) {
            val doc = document ?: return@Timer
            if (suppressReload) return@Timer
            loadSafely(doc.text)
        }.apply { isRepeats = false }

    init {
        // Give the split panes a meaningful initial extent, so the preview isn't squeezed to
        // zero width inside TextEditorWithPreview's splitter (the tool-window path uses a
        // BorderLayout holder and always has room, which is why only the tab looked blank).
        root.preferredSize = Dimension(480, 360)
        root.minimumSize = Dimension(200, 120)
        showLoading()
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
        startBackgroundInit()
    }

    private fun showLoading() {
        root.removeAll()
        root.add(
            JLabel(
                "<html><div style=\"text-align:center\">Loading SvgEasy…<br>" +
                    "<span style=\"color:#888888\">preparing the native renderer</span></div></html>",
            ).apply {
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            },
            BorderLayout.CENTER,
        )
        root.revalidate()
        root.repaint()
    }

    /**
     * Locates the native renderer OFF the EDT. The very first call extracts JNA's jnidispatch
     * from an IDE lib jar — on a cold cache that previously blocked editor creation (inside
     * `writeIntentReadAction` on the EDT) for >20 s. The result is cached process-wide, so later
     * opens skip this step. Only the (fast) Swing canvas build and initial SVG parse happen back
     * on the EDT; actual rendering is async inside [SvgEditorPanel].
     */
    private fun startBackgroundInit() {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            val renderer = SvgBridgeLoader.loadOrNull()
            if (disposed) return@executeOnPooledThread
            val sidecar =
                if (renderer != null) {
                    runCatching {
                        SidecarLoader.resolveOrNull()?.let { SidecarClient(listOf(it)) }
                    }.onFailure { LOG.warn("preview: sidecar resolve failed; using in-process pipeline", it) }
                        .getOrNull()
                } else {
                    null
                }
            val builtSidecar = sidecar
            SwingUtilities.invokeLater {
                if (disposed) {
                    builtSidecar?.close()
                    return@invokeLater
                }
                if (renderer == null) {
                    // Native renderer missing: explain how to supply it.
                    root.removeAll()
                    root.add(NativeLibGuidePanel(SvgBridgeLoader.describeAttempts()), BorderLayout.CENTER)
                    root.revalidate()
                    root.repaint()
                    return@invokeLater
                }
                val r = renderer
                var canvas: SvgEditorPanel? = null
                try {
                    val c =
                        SvgEditorPanel(r, asyncRendering = true, sidecar = builtSidecar).apply {
                            onEdit = { writeBack() }
                        }
                    canvas = c
                    panel = c
                    ownedSidecar = builtSidecar
                    try {
                        toolbar = SvgEasyToolbar.forPanel(c)
                    } catch (t: Throwable) {
                        LOG.warn("preview: toolbar build failed", t)
                        toolbar = null
                    }
                    c.onStatus = { refreshInfo() }
                    c.onRenderError = { showParseError(it) }
                    // Parse the current document text; a malformed SVG degrades to the visible
                    // parse-error notice. fileText() reflects any edits the user already made
                    // while the renderer was loading.
                    try {
                        fileText()?.let { c.loadSvg(it) }
                    } catch (t: Throwable) {
                        LOG.warn("preview: initial load failed", t)
                        showParseError(t)
                        return@invokeLater
                    }
                    showCanvas()
                } catch (t: Throwable) {
                    LOG.warn("preview: canvas build failed", t)
                    canvas?.dispose()
                    panel = null
                    ownedSidecar = null
                    builtSidecar?.close()
                    showParseError(t)
                }
            }
        }
    }

    /** Document text when available, else the file bytes; null when neither is readable. */
    private fun fileText(): String? =
        document?.text
            ?: ApplicationManager.getApplication().runReadAction(
                Computable<String?> { readFileBytes() },
            )

    private fun readFileBytes(): String? =
        runCatching { String(file.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()

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
        val bar = toolbar
        if (canvas.parent === root && (bar == null || bar.parent === root)) return
        root.removeAll()
        bar?.let { root.add(it, BorderLayout.NORTH) }
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
        disposed = true
        document?.removeDocumentListener(documentListener)
        reloadDebounce.stop()
        panel?.dispose()
        panel = null
        ownedSidecar?.close()
        ownedSidecar = null
    }
}
