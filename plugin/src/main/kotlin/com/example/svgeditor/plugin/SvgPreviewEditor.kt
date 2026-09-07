package com.example.svgeditor.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.AnActionLink
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.Timer

private val LOG = Logger.getInstance("SvgEasy")

/**
 * Editor that shows the SVG source (a standard [TextEditor], left) side-by-side with the
 * interactive design canvas ([SvgPreviewPanel], right) in a single tab.
 *
 * This deliberately does NOT extend the platform's `TextEditorWithPreview`. That wrapper's split
 * internal layout has regressed on newer IDEA builds (2025.x+, including 2026.2) with the preview
 * pane rendering blank even when everything underneath works — and this plugin builds against
 * IDEA 2023.2.5 with `untilBuild=null`, so it installs unverified on whatever IDE the user runs.
 * The wrapper's known, non-class-documentable blank-preview behavior is exactly what bit the
 * SvgEasy tab: the tool window (a plain Swing [SvgEditorPanel]) rendered fine, but the wrapped
 * preview pane was empty.
 *
 * Instead the split is a plain Swing [JSplitPane], which the platform never sizes or owns, so the
 * preview always gets a real, non-zero extent and renders — on every IDEA version. A small tab
 * toolbar preserves the three split modes (editor-only / split / preview-only).
 *
 * Both sides share one document through [SvgPreviewPanel]: edits in the source reload the canvas,
 * and canvas commits write back. Any construction failure degrades to [SvgEasyFallbackPanel] so
 * the tab always renders something, never a silent blank.
 */
class SvgPreviewEditor(
    private val project: Project,
    private val file: VirtualFile,
) : FileEditor, UserDataHolderBase() {
    private val textEditor: TextEditor =
        TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
    private val preview = createPreviewSafely(project, file)

    private var mode = Mode.SPLIT

    private var fallbackApplied = false

    private val split =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, textEditor.component, preview.component).apply {
            isContinuousLayout = true
            resizeWeight = 0.5
            border = null
            textEditor.component.minimumSize = Dimension(120, 120)
        }

    private val root =
        JPanel(BorderLayout()).apply {
            add(buildTabToolbar(), BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
            preferredSize = Dimension(960, 560)
            minimumSize = Dimension(320, 240)
            addComponentListener(
                object : ComponentAdapter() {
                    override fun componentShown(e: ComponentEvent) {
                        splitGuardTicks = 0
                        LOG.info(
                            "editor: shown split=${split.width}x${split.height} divider=${split.dividerLocation} " +
                                "right=${preview.component.width}x${preview.component.height}",
                        )
                        enforceSplitLayout("shown")
                    }

                    override fun componentResized(e: ComponentEvent) {
                        splitGuardTicks = 0
                        enforceSplitLayout("resized")
                    }
                },
            )
        }

    private val splitGuard =
        Timer(SPLIT_GUARD_PERIOD_MS) { enforceSplitLayout("guard") }.apply { isRepeats = true }

    private var splitGuardTicks = 0

    /**
     * Keeps re-applying a pixel-based divider location until the preview pane has a real width.
     * `setDividerLocation(proportional)` is silently ignored by JSplitPane while the hierarchy is
     * still being laid out (width == 0), which is how the preview ended up zero-width blank.
     */
    private fun enforceSplitLayout(reason: String) {
        if (mode != Mode.SPLIT || !root.isShowing) {
            splitGuard.stop()
            return
        }
        val right = preview.component
        if (right.width >= MIN_PREVIEW_WIDTH) {
            splitGuard.stop()
            return
        }
        if (splitGuardTicks >= SPLIT_GUARD_MAX_TICKS) {
            splitGuard.stop()
            return
        }
        splitGuardTicks++
        if (split.width > 0) {
            split.dividerLocation = split.width / 2
        }
        LOG.info(
            "editor: $reason#$splitGuardTicks split=${split.width}x${split.height} " +
                "divider=${split.dividerLocation} left=${textEditor.component.width}x${textEditor.component.height} " +
                "right=${right.width}x${right.height}",
        )
        if (!splitGuard.isRunning) splitGuard.start()
    }

    /**
     * Last-resort layout for environments where JSplitPane never gives the preview a real width
     * despite repeated pixel-based retries: swaps the splitter for a borderless GridBagLayout
     * 50/50 pair (no divider, no timing involved) and pins a one-line banner at the bottom so
     * the condition is visible on screen — no log digging required.
     */
    private fun engageFallback(reason: String) {
        if (fallbackApplied) return
        fallbackApplied = true
        splitGuard.stop()
        LOG.warn(
            "editor: fallback engaged ($reason) split=${split.width}x${split.height} " +
                "right=${preview.component.width}x${preview.component.height}",
        )
        val pane =
            JPanel(GridBagLayout()).apply {
                fun constraints(gridx: Int): GridBagConstraints =
                    GridBagConstraints().apply {
                        this.gridx = gridx
                        weightx = 0.5
                        fill = GridBagConstraints.BOTH
                    }
                add(textEditor.component, constraints(0))
                add(preview.component, constraints(1))
            }
        root.removeAll()
        root.add(buildTabToolbar(), BorderLayout.NORTH)
        root.add(pane, BorderLayout.CENTER)
        root.add(
            JLabel(
                "<html><b>SvgEasy</b>: splitter failed ($reason) — switched to fixed 50/50 layout.</html>",
            ).apply { border = BorderFactory.createEmptyBorder(2, 8, 2, 8) },
            BorderLayout.SOUTH,
        )
        root.revalidate()
        root.repaint()
        applyMode(mode)
    }

    private companion object {
        private const val MIN_PREVIEW_WIDTH = 80
        private const val SPLIT_GUARD_PERIOD_MS = 150
        private const val SPLIT_GUARD_MAX_TICKS = 40
    }

    /** The compositional view modes exposed on the tab by the toolbar. */
    private enum class Mode { SOURCE, SPLIT, PREVIEW }

    private fun modeAction(
        label: String,
        tip: String,
        m: Mode,
    ): AnAction =
        object : AnAction(label, tip, null) {
            override fun actionPerformed(e: AnActionEvent) = applyMode(m)
        }

    private fun buildTabToolbar(): JComponent {
        val toolbar = javax.swing.JToolBar().apply {
            isFloatable = false
            border = null
        }
        toolbar.add(AnActionLink(modeAction("Source", "Show source only", Mode.SOURCE), "SvgEasy.Source"))
        toolbar.add(AnActionLink(modeAction("Split", "Show source and preview", Mode.SPLIT), "SvgEasy.Split"))
        toolbar.add(AnActionLink(modeAction("Preview", "Show preview only", Mode.PREVIEW), "SvgEasy.Preview"))
        return toolbar
    }

    private fun applyMode(mode: Mode) {
        this.mode = mode
        val left = textEditor.component
        val right = preview.component
        if (fallbackApplied) {
            left.isVisible = mode != Mode.PREVIEW
            right.isVisible = mode != Mode.SOURCE
            root.revalidate()
            root.repaint()
            return
        }
        // setDividerLocation(double) treats the value as a ratio of the full divider range,
        // so 0.5 centres the split; JSplitPane then only lays out visible children, so hiding
        // either side collapses it automatically.
        split.setDividerLocation(if (mode == Mode.SPLIT) 0.5 else 0.0)
        root.revalidate()
        root.repaint()
        SwingUtilities.invokeLater {
            if (mode == Mode.SPLIT && split.isShowing) split.dividerLocation = split.width / 2
        }
    }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = textEditor.component

    override fun getName(): String = "SvgEasy"

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun getFile(): VirtualFile? = file

    override fun dispose() {
        splitGuard.stop()
        textEditor.dispose()
        if (preview is SvgPreviewPanel) preview.dispose()
    }

    override fun <T : Any?> getUserData(key: Key<T>): T? = super.getUserData(key)

    override fun <T : Any?> putUserData(
        key: Key<T>,
        value: T?,
    ) = super.putUserData(key, value)
}

/** Builds the preview panel, degrading to [SvgEasyFallbackPanel] instead of throwing. */
private fun createPreviewSafely(project: Project, file: VirtualFile): FileEditor =
    try {
        SvgPreviewPanel(project, file).also { LOG.info("editor: preview created: SvgPreviewPanel") }
    } catch (t: Throwable) {
        LOG.warn("editor: SvgPreviewPanel failed, using fallback", t)
        SvgEasyFallbackPanel(t)
    }

/**
 * Last-resort [FileEditor] shown when the real preview cannot be built; it displays the failure
 * reason so problems stay visible (and diagnosable) instead of rendering an empty tab.
 */
internal class SvgEasyFallbackPanel(error: Throwable) : JPanel(BorderLayout()), FileEditor {
    private val userDataHolder = UserDataHolderBase()

    init {
        val detail =
            (error.message ?: error.javaClass.name)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .take(400)
        add(
            javax.swing.JLabel(
                "<html><b>SvgEasy could not open this view</b><br>" +
                    "<span style=\"color:#888888\">$detail</span></html>",
            ).apply {
                horizontalAlignment = javax.swing.SwingConstants.CENTER
                verticalAlignment = javax.swing.SwingConstants.CENTER
            },
            BorderLayout.CENTER,
        )
    }

    override fun getComponent(): JComponent = this

    override fun getPreferredFocusedComponent(): JComponent = this

    override fun getName(): String = "SvgEasy"

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun getFile(): VirtualFile? = null

    override fun dispose() {}

    override fun <T : Any?> getUserData(key: Key<T>): T? = userDataHolder.getUserData(key)

    override fun <T : Any?> putUserData(
        key: Key<T>,
        value: T?,
    ) = userDataHolder.putUserData(key, value)
}