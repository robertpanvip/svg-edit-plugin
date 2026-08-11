package com.example.svgeditor.plugin

import com.example.svgeditor.core.ResvgBridge
import com.example.svgeditor.core.Samples
import com.example.svgeditor.core.SvgEditorPanel
import com.example.svgeditor.core.SvgRenderer
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
        val panel = SvgEditorPanel(loadBridge())
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

    private fun loadBridge(): SvgRenderer {
        val candidates =
            listOf(
                "resvg_bridge", // bundled on java.library.path
                "../native/resvg_bridge/target/debug/resvg_bridge",
                "../native/resvg_bridge/target/release/resvg_bridge",
            )
        var last: Throwable? = null
        for (c in candidates) {
            try {
                return ResvgBridge.load(c)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("Could not load resvg_bridge native library (tried $candidates)", last)
    }
}
