package com.example.svgeditor.plugin

import com.example.svgeditor.core.EditorIcon
import com.example.svgeditor.core.IconResolver
import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Resolves editor toolbar icons to the official IntelliJ `AllIcons`, so the plugin toolbar
 * matches the IDEA look exactly. The mapping mirrors the buttons of the built-in image viewer:
 *   OPEN        -> AllIcons.Actions.MenuOpen
 *   SAVE        -> AllIcons.Actions.MenuSave
 *   ZOOM_IN/OUT -> AllIcons.General.ZoomIn / ZoomOut
 *   ACTUAL_SIZE -> AllIcons.General.ActualZoom   (the image viewer's 100% action)
 *   FIT         -> AllIcons.General.FitContent
 *   GRID        -> AllIcons.Graph.Grid
 *   CHESS       -> AllIcons.Actions.Checked      (transparency toggle)
 *   THEME       -> AllIcons.Actions.QuickChange
 * If a constant name differs in your IDEA version, adjust only the line below — the rest of the
 * code is unaffected.
 */
object IdeaIconResolver : IconResolver {
    override fun resolve(icon: EditorIcon): Icon =
        when (icon) {
            EditorIcon.OPEN -> AllIcons.Actions.MenuOpen
            EditorIcon.SAVE -> AllIcons.Actions.MenuSave
            EditorIcon.ZOOM_IN -> AllIcons.General.ZoomIn
            EditorIcon.ZOOM_OUT -> AllIcons.General.ZoomOut
            EditorIcon.ACTUAL_SIZE -> AllIcons.General.ActualZoom
            EditorIcon.FIT -> AllIcons.General.FitContent
            EditorIcon.GRID -> AllIcons.Graph.Grid
            EditorIcon.CHESS -> AllIcons.Actions.Checked
            EditorIcon.THEME -> AllIcons.Actions.QuickChange
        }
}
