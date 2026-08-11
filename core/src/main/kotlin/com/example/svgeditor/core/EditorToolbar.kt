package com.example.svgeditor.core

import java.awt.Dimension
import java.awt.Insets
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JSeparator
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.BorderFactory
import javax.swing.UIManager

/**
 * Semantic toolbar icons. The standalone app resolves these to `null` (text fallback); the
 * IntelliJ plugin resolves them to `com.intellij.icons.AllIcons` so the toolbar matches the
 * IDEA look exactly. Keeping the mapping in [IconResolver] lets the SAME toolbar builder serve
 * both runtimes with zero IntelliJ dependencies in `core`.
 */
enum class EditorIcon {
    OPEN, SAVE, ZOOM_IN, ZOOM_OUT, ACTUAL_SIZE, FIT, GRID, CHESS, THEME
}

/** Resolves a semantic [EditorIcon] to a Swing [Icon], or `null` to fall back to a text label. */
interface IconResolver {
    fun resolve(icon: EditorIcon): Icon?
}

/** Default resolver used by the standalone app: no icons, so the toolbar shows text labels. */
object TextIconResolver : IconResolver {
    override fun resolve(icon: EditorIcon): Icon? = null
}

/** Optional toolbar callbacks. A `null` entry hides the corresponding button. */
data class ToolbarActions(
    val onOpen: (() -> Unit)? = null,
    val onSave: (() -> Unit)? = null,
    val onToggleTheme: (() -> Unit)? = null,
)

/**
 * Build an IDEA-aligned editor toolbar. Button order mirrors the official IntelliJ image viewer:
 *   Open / Save | Zoom out / Zoom in / Actual size (100%) / Fit | Grid / Chess | Theme
 * Icons are taken from [resolver] when available, otherwise text labels are used. The builder
 * lives in `core` (pure Swing, no IntelliJ/FlatLaf dependency) so both the standalone app and
 * the IDEA plugin share one implementation.
 */
fun createEditorToolbar(
    panel: SvgEditorPanel,
    resolver: IconResolver,
    actions: ToolbarActions = ToolbarActions(),
): JToolBar {
    fun button(
        icon: EditorIcon,
        text: String,
        tooltip: String,
        action: () -> Unit,
    ): JButton {
        val resolved = resolver.resolve(icon)
        val b = if (resolved != null) JButton(resolved) else JButton(text)
        b.toolTipText = tooltip
        b.isFocusable = false
        b.margin = Insets(4, 6, 4, 6)
        b.addActionListener { action() }
        return b
    }

    fun toggle(
        icon: EditorIcon,
        text: String,
        tooltip: String,
        initial: Boolean,
        action: (Boolean) -> Unit,
    ): JToggleButton {
        val resolved = resolver.resolve(icon)
        val b = if (resolved != null) JToggleButton(resolved) else JToggleButton(text)
        b.toolTipText = tooltip
        b.isFocusable = false
        b.margin = Insets(4, 6, 4, 6)
        b.isSelected = initial
        b.addActionListener { action(b.isSelected) }
        return b
    }

    return JToolBar().apply {
        isFloatable = false
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor"))

        if (actions.onOpen != null) add(button(EditorIcon.OPEN, "Open", "Open SVG file", actions.onOpen))
        if (actions.onSave != null) add(button(EditorIcon.SAVE, "Save", "Save SVG file", actions.onSave))
        if (actions.onOpen != null || actions.onSave != null) addSeparator(Dimension(8, 0))

        add(button(EditorIcon.ZOOM_OUT, "-", "Zoom out", panel::zoomOut))
        add(button(EditorIcon.ZOOM_IN, "+", "Zoom in", panel::zoomIn))
        add(button(EditorIcon.ACTUAL_SIZE, "100%", "Actual size (100%)", panel::actualSize))
        add(button(EditorIcon.FIT, "Fit", "Fit to window", panel::fitView))
        addSeparator(Dimension(8, 0))
        add(toggle(EditorIcon.GRID, "Grid", "Toggle image-pixel grid", false, panel::setGrid))
        add(toggle(EditorIcon.CHESS, "Chess", "Toggle transparency chessboard", true, panel::setChessboard))
        if (actions.onToggleTheme != null) {
            addSeparator(Dimension(8, 0))
            add(button(EditorIcon.THEME, "Theme", "Toggle Light / Darcula", actions.onToggleTheme))
        }
    }
}
