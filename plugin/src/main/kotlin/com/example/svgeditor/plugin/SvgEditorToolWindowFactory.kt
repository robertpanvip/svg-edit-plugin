package com.example.svgeditor.plugin

import com.example.svgeditor.core.Samples
import com.example.svgeditor.core.SvgEditorPanel
import com.example.svgeditor.core.createEditorToolbar
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Registers the SVG editor as a right-docked tool window. Loads the `resvg_bridge` native
 * library (bundled, or built locally) and wires it into [SvgEditorPanel].
 *
 * The toolbar reuses the same [createEditorToolbar] builder as the standalone app, but resolves
 * icons through [IdeaIconResolver] (official IntelliJ `AllIcons`) so it matches the IDEA look.
 */
class SvgEditorToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = SvgEditorPanel(SvgBridgeLoader.load())
        panel.loadSvg(Samples.SIMPLE)
        val toolbar = createEditorToolbar(panel, IdeaIconResolver)
        val container =
            JPanel(BorderLayout()).apply {
                add(toolbar, BorderLayout.NORTH)
                add(panel, BorderLayout.CENTER)
            }
        val content = ContentFactory.getInstance().createContent(container, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
