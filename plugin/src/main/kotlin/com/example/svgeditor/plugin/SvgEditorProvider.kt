package com.example.svgeditor.plugin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Makes [SvgPreviewEditor] available for `.svg` files. Registered with `order="last"` and
 * [FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR], so opening an `.svg` file keeps the built-in
 * IDEA image viewer as the first tab and adds SvgEasy (text + design canvas) as a second tab
 * the user can click to switch to — mirroring the coexistence approach of SvgEazy
 * (`PLACE_BEFORE_DEFAULT_EDITOR` there, after here so the original viewer stays first).
 *
 * To make SvgEasy the *default* `.svg` editor instead (replacing the image viewer), change the
 * registration to `order="first"`.
 */
class SvgEditorProvider : FileEditorProvider, DumbAware {
    private val log = Logger.getInstance("SvgEasy")

    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = file.extension.equals("svg", ignoreCase = true)

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

    override fun getEditorTypeId(): String = "SvgEasy.text.editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
