package com.pan.svg.plugin

import com.pan.svg.core.Samples
import com.pan.svg.core.SidecarClient
import com.pan.svg.core.SvgEditorPanel
import com.pan.svg.core.SvgRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.nio.charset.StandardCharsets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Registers the SVG editor as a right-docked tool window. Loads the `resvg_bridge` native
 * library (bundled, or built locally) and wires it into [SvgEditorPanel].
 *
 * The tool window opens instantly with a "loading" placeholder; the native library is located on
 * a pooled thread — the very first lookup extracts JNA's jnidispatch from an IDE lib jar and can
 * take tens of seconds on a cold cache, so it must never run on the EDT — and the canvas is
 * assembled once it is ready. A later lookup is instant because [SvgBridgeLoader] caches a
 * successful load process-wide.
 *
 * When the native library cannot be loaded (typically: plugin zip built on another OS, so the
 * library for this platform is not bundled), the tool window shows [NativeLibGuidePanel]
 * explaining how to supply the library — instead of failing with a blank panel.
 *
 * The tool window follows the editor selection: whenever the user selects a `.svg` file in the
 * editor (via [FileEditorManagerListener]), the same file is loaded into the tool window. Parse
 * failures are shown as an inline notice instead of crashing the panel. With no SVG file open,
 * it falls back to the built-in [Samples.SIMPLE] sketch.
 *
 * The panel runs the async render pipeline (background renderer thread + EDT-only blitting);
 * a [Disposable] releases it (and the message bus connection) when the tool window content is
 * disposed.
 *
 * The toolbar is a native platform `ActionToolbar` (see [SvgEasyToolbar]) driven by the same
 * `AllIcons` actions as the built-in image viewer, so it matches the IDEA look exactly.
 */
class SvgEditorToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val lifecycle = Disposable {}
        val state = ContentState()
        val holder = JPanel(BorderLayout())
        holder.add(loadingLabel(), BorderLayout.CENTER)
        val content = ContentFactory.getInstance().createContent(holder, "", false)
        content.setDisposer(
            Disposable {
                Disposer.dispose(lifecycle)
                state.disposed = true
                state.panel?.dispose()
                state.panel = null
                state.ownedSidecar?.close()
                state.ownedSidecar = null
            },
        )
        toolWindow.contentManager.addContent(content)

        // First bridge load reads central directories of IDE lib jars to extract JNA's
        // jnidispatch (observed >20 s on a cold cache): run it on a pooled thread, then build the
        // UI body on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val renderer = SvgBridgeLoader.loadOrNull()
            SwingUtilities.invokeLater {
                if (state.disposed) return@invokeLater
                installBody(project, holder, renderer, lifecycle, state)
            }
        }
    }

    /** Mutable state shared between the content disposer and the deferred body install. */
    private class ContentState {
        @Volatile var disposed = false
        @Volatile var panel: SvgEditorPanel? = null
        @Volatile var ownedSidecar: SidecarClient? = null
    }

    private fun loadingLabel(): JComponent =
        JLabel(
            "<html><div style=\"text-align:center\">Loading SvgEasy…<br>" +
                "<span style=\"color:#888888\">preparing the native renderer</span></div></html>",
        ).apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
        }

    /** Assembles the real tool window body into [holder]; called on the EDT. */
    private fun installBody(
        project: Project,
        holder: JPanel,
        renderer: SvgRenderer?,
        lifecycle: Disposable,
        state: ContentState,
    ) {
        if (renderer != null) {
            val sidecarCommand = SidecarLoader.resolveOrNull()
            state.ownedSidecar = sidecarCommand?.let { SidecarClient(listOf(it)) }
            val editorPanel = SvgEditorPanel(renderer, asyncRendering = true, sidecar = state.ownedSidecar)
            state.panel = editorPanel
            val toolbar = SvgEasyToolbar.forPanel(editorPanel)
            val editorView =
                JPanel(BorderLayout()).apply {
                    add(toolbar, BorderLayout.NORTH)
                    add(editorPanel, BorderLayout.CENTER)
                }

            fun showEditorView() {
                if (holder.componentCount == 1 && holder.getComponent(0) === editorView) return
                holder.removeAll()
                holder.add(editorView, BorderLayout.CENTER)
                holder.revalidate()
                holder.repaint()
            }

            fun showError(t: Throwable) {
                val detail = escapeHtml(t.message ?: t.javaClass.simpleName).take(200)
                holder.removeAll()
                holder.add(
                    JLabel(
                        "<html><b>This SVG could not be rendered by SvgEasy</b><br>" +
                            "<span style=\"color:#888888\">$detail</span></html>",
                    ).apply {
                        horizontalAlignment = SwingConstants.CENTER
                        verticalAlignment = SwingConstants.CENTER
                    },
                    BorderLayout.CENTER,
                )
                holder.revalidate()
                holder.repaint()
            }

            fun load(text: String?) {
                if (text == null) {
                    try {
                        editorPanel.loadSvg(Samples.SIMPLE)
                        showEditorView()
                    } catch (t: Throwable) {
                        showError(t)
                    }
                    return
                }
                try {
                    editorPanel.loadSvg(text)
                    showEditorView()
                } catch (t: Throwable) {
                    showError(t)
                }
            }

            fun show(file: VirtualFile?) {
                load(file?.let { readText(it) })
            }

            project.messageBus.connect(lifecycle).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER,
                object : FileEditorManagerListener {
                    override fun selectionChanged(event: FileEditorManagerEvent) {
                        val file = event.newFile ?: return
                        if (!file.isSvg()) return
                        show(file)
                    }
                },
            )

            val selected =
                FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.isSvg() }
            show(selected)
        } else {
            holder.removeAll()
            holder.add(NativeLibGuidePanel(SvgBridgeLoader.describeAttempts()), BorderLayout.CENTER)
            holder.revalidate()
            holder.repaint()
        }
    }

    private fun VirtualFile.isSvg(): Boolean = extension?.equals("svg", ignoreCase = true) == true

    /** Document text when available, else the raw file bytes; null when neither is readable. */
    private fun readText(file: VirtualFile): String? {
        if (!file.isValid || file.isDirectory) return null
        val fromDocument =
            runCatching { FileDocumentManager.getInstance().getDocument(file)?.text }.getOrNull()
        return fromDocument
            ?: runCatching { String(file.contentsToByteArray(), StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
