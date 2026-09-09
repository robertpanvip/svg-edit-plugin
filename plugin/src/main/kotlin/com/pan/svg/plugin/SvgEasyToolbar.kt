package com.pan.svg.plugin

import com.pan.svg.core.EditorIcons
import com.pan.svg.core.SvgEditorPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import javax.swing.Icon
import javax.swing.JComponent

/**
 * SvgEasy toolbar built from platform actions ([ActionToolbar] + [AnAction]), so it renders with
 * the exact same native look as the IDE's own editor toolbars (correct insets, hover, separator
 * and spacing styles) instead of hand-rolled Swing buttons. The button set and order mirror the
 * built-in image viewer's `Images.EditorToolbar` exactly: transparency chessboard, grid, a
 * separator, then zoom in / zoom out / actual size / fit. (The image viewer's color picker,
 * change-background and split-mode actions are omitted because SvgEasy's design canvas has no
 * equivalent concept.)
 */
object SvgEasyToolbar {
    /** Builds the native toolbar operating on [panel]; add its component at NORTH of a layout. */
    fun forPanel(panel: SvgEditorPanel): JComponent {
        val group =
            DefaultActionGroup(
                MoveToolAction(panel),
                MarqueeToolAction(panel),
                Separator.create(),
                ChessboardToggleAction(panel),
                GridToggleAction(panel),
                Separator.create(),
                ZoomInAction(panel),
                ZoomOutAction(panel),
                ActualSizeAction(panel),
                FitAction(panel),
            )
        val toolbar =
            ActionManager.getInstance().createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, group, true)
        toolbar.targetComponent = panel
        return toolbar.component
    }

    private abstract class PanelAction(
        protected val panel: SvgEditorPanel,
        text: String,
        description: String,
        icon: Icon,
    ) : AnAction(text, description, icon) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = panel.isShowing
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private abstract class PanelToggleAction(
        protected val panel: SvgEditorPanel,
        text: String,
        description: String,
        icon: Icon,
    ) : ToggleAction(text, description, icon) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = panel.isShowing
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    /**
     * Mutually exclusive tool toggles ("Move" vs "Box Select"). Both read the live tool from
     * [SvgEditorPanel.getTool], so clicking one automatically un-presses the other on the next
     * toolbar refresh; clicking the already-active tool keeps it active (radio behaviour). Each
     * tool carries its own semantic icon (see [com.pan.svg.core.EditorIcons]) so the two buttons
     * are visually distinct in the icon-first IDE toolbar.
     */
    private abstract class ToolToggleAction(
        protected val panel: SvgEditorPanel,
        text: String,
        description: String,
        icon: Icon,
        private val tool: SvgEditorPanel.Tool,
    ) : ToggleAction(text, description, icon) {
        override fun isSelected(e: AnActionEvent): Boolean = panel.getTool() == tool

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) panel.setTool(tool)
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = panel.isShowing
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private class MoveToolAction(panel: SvgEditorPanel) :
        ToolToggleAction(
            panel,
            "Move",
            "Move: select, drag, resize and rotate elements",
            EditorIcons.moveTool(),
            SvgEditorPanel.Tool.MOVE,
        )

    private class MarqueeToolAction(panel: SvgEditorPanel) :
        ToolToggleAction(
            panel,
            "Box Select",
            "Box Select: drag a rectangle to select",
            EditorIcons.boxSelectTool(),
            SvgEditorPanel.Tool.MARQUEE,
        )

    private class ZoomInAction(panel: SvgEditorPanel) :
        PanelAction(panel, "Zoom In", "Zoom in", AllIcons.General.ZoomIn) {
        override fun actionPerformed(e: AnActionEvent) {
            panel.zoomIn()
        }
    }

    private class ZoomOutAction(panel: SvgEditorPanel) :
        PanelAction(panel, "Zoom Out", "Zoom out", AllIcons.General.ZoomOut) {
        override fun actionPerformed(e: AnActionEvent) {
            panel.zoomOut()
        }
    }

    private class ActualSizeAction(panel: SvgEditorPanel) :
        PanelAction(panel, "Actual Size", "Actual size (100%)", AllIcons.General.ActualZoom) {
        override fun actionPerformed(e: AnActionEvent) {
            panel.actualSize()
        }
    }

    private class FitAction(panel: SvgEditorPanel) :
        PanelAction(panel, "Fit to Window", "Fit to window", AllIcons.General.FitContent) {
        override fun actionPerformed(e: AnActionEvent) {
            panel.fitView()
        }
    }

    private class GridToggleAction(panel: SvgEditorPanel) :
        PanelToggleAction(panel, "Show Grid", "Show or hide the image-pixel grid", AllIcons.Graph.Grid) {
        override fun isSelected(e: AnActionEvent): Boolean = panel.isGrid()

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            panel.setGrid(state)
        }
    }

    private class ChessboardToggleAction(panel: SvgEditorPanel) :
        PanelToggleAction(
            panel,
            "Show Transparency",
            "Show or hide the transparency chessboard",
            AllIcons.Actions.Preview,
        ) {
        override fun isSelected(e: AnActionEvent): Boolean = panel.isChessboard()

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            panel.setChessboard(state)
        }
    }
}
