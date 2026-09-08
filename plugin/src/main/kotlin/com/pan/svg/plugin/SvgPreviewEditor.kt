package com.pan.svg.plugin

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
 * interactive design canvas ([SvgPreviewPanel], right) in a single tab, via the platform's own
 * [TextEditorWithPreview] (3-arg form; its default layout already is editor + preview).
 *
 * The platform persists the last view mode in the application-level `SvgEasyLayout` property and
 * re-applies it every time the file is opened; a stale "editor only" value therefore keeps the
 * preview component hidden and the right-hand canvas never becomes showing (the blank-canvas
 * symptom). [init] pins the layout back to editor + preview on every construction: it re-shows
 * the preview immediately and, because the written value equals the default layout, the stale
 * property is dropped from PropertiesComponent.
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
    ) {
    init {
        setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
        LOG.info("editor: layout pinned to SHOW_EDITOR_AND_PREVIEW (effective=$layout)")
    }
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
