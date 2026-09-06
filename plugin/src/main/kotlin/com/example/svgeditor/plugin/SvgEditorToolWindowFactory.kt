package com.example.svgeditor.plugin

import com.example.svgeditor.core.Samples
import com.example.svgeditor.core.SvgEditorPanel
import com.example.svgeditor.core.createEditorToolbar
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Registers the SVG editor as a right-docked tool window. Loads the `resvg_bridge` native
 * library (bundled, or built locally) and wires it into [SvgEditorPanel].
 *
 * The panel runs the async render pipeline (background renderer thread + EDT-only blitting);
 * a [Disposable] releases it when the tool window content is disposed.
 *
 * The toolbar reuses the same [createEditorToolbar] builder as the standalone app, but resolves
 * icons through [IdeaIconResolver] (official IntelliJ `AllIcons`) so it matches the IDEA look.
 */
class SvgEditorToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = SvgEditorPanel(SvgBridgeLoader.load(), asyncRendering = true)
        panel.loadSvg(Samples.SIMPLE)
        val toolbar = createEditorToolbar(panel, IdeaIconResolver)
        val container =
            JPanel(BorderLayout()).apply {
                add(toolbar, BorderLayout.NORTH)
                add(panel, BorderLayout.CENTER)
            }
        val content = ContentFactory.getInstance().createContent(container, "", false)
        content.setDisposer(Disposable { panel.dispose() })
        toolWindow.contentManager.addContent(content)
    }
}
