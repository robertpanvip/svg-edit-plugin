package com.example.svgeditor.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

private val LOG = Logger.getInstance("SvgEasy")

/**
 * Editor that shows the SVG source (a standard [TextEditor], left) side-by-side with the
 * interactive design canvas ([SvgPreviewPanel], right) in a single tab.
 *
 * The split layout is the platform's own [TextEditorWithPreview]: it owns the splitter and its
 * whole lifecycle, and ships native editor-only / split / preview-only tab actions. We
 * deliberately add NO custom divider/resize logic — repeatedly forcing sizes during editor
 * initialization stalls the IDE's FileEditor lifecycle, so the platform layout is left alone.
 *
 * The preview is created through [createPreviewSafely]: any construction failure degrades to
 * [SvgEasyFallbackPanel] (a visible error notice), so editor creation itself can never throw
 * and leave a silent blank.
 */
class SvgPreviewEditor(
    project: Project,
    file: VirtualFile,
) : TextEditorWithPreview(
        TextEditorProvider.getInstance().createEditor(project, file) as TextEditor,
        createPreviewSafely(project, file),
        "SvgEasy",
        TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW,
    ) {
    // Force the platform's view-mode (split) actions to render on the tab, so the user can switch
    // between editor-only / split / preview-only right from the tab.
    override fun isShowActionsInTabs(): Boolean = true
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
            JLabel(
                "<html><b>SvgEasy could not open this view</b><br>" +
                    "<span style=\"color:#888888\">$detail</span></html>",
            ).apply {
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
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
