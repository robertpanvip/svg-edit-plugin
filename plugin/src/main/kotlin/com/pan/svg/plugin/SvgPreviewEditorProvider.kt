package com.pan.svg.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Makes [SvgPreviewEditor] available for `.svg` files. [FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR]
 * puts the SvgEasy (text + design canvas) composite first in the tab, with the built-in image
 * viewer kept as a secondary editor.
 */
class SvgPreviewEditorProvider : FileEditorProvider, DumbAware {
    private val log = Logger.getInstance("SvgEasy")

    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = !file.isDirectory && file.extension?.equals("svg", ignoreCase = true) == true

    /** Never throws: even a failing [SvgPreviewEditor] degrades to a visible error tab. */
    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor =
        try {
            log.info("provider: creating SvgPreviewEditor for ${file.name}")
            SvgPreviewEditor(project, file).also { log.info("provider: SvgPreviewEditor created for ${file.name}") }
        } catch (t: Throwable) {
            log.warn("provider: SvgPreviewEditor construction failed, using fallback", t)
            SvgEasyFallbackPanel(t)
        }

    override fun getEditorTypeId(): String = "SVG_PREVIEW"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}
