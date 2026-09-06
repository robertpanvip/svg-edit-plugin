package com.example.svgeditor.plugin

import com.example.svgeditor.core.Samples
import com.example.svgeditor.core.SvgEditorPanel
import com.example.svgeditor.core.createEditorToolbar
import com.intellij.openapi.Disposable
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

/**
 * Registers the SVG editor as a right-docked tool window. Loads the `resvg_bridge` native
 * library (bundled, or built locally) and wires it into [SvgEditorPanel].
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
 * The toolbar reuses the same [createEditorToolbar] builder as the standalone app, but resolves
 * icons through [IdeaIconResolver] (official IntelliJ `AllIcons`) so it matches the IDEA look.
 */
class SvgEditorToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val renderer = SvgBridgeLoader.loadOrNull()
        val lifecycle = Disposable {}
        var panel: SvgEditorPanel? = null
        val body: JComponent =
            if (renderer != null) {
                val editorPanel = SvgEditorPanel(renderer, asyncRendering = true)
                panel = editorPanel
                val toolbar = createEditorToolbar(editorPanel, IdeaIconResolver)
                val editorView =
                    JPanel(BorderLayout()).apply {
                        add(toolbar, BorderLayout.NORTH)
                        add(editorPanel, BorderLayout.CENTER)
                    }
                val holder = JPanel(BorderLayout())

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

                holder
            } else {
                NativeLibGuidePanel(SvgBridgeLoader.describeAttempts())
            }
        val content = ContentFactory.getInstance().createContent(body, "", false)
        content.setDisposer(
            Disposable {
                Disposer.dispose(lifecycle)
                panel?.dispose()
            },
        )
        toolWindow.contentManager.addContent(content)
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
