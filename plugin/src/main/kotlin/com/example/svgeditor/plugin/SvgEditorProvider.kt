package com.example.svgeditor.plugin

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Makes [SvgPreviewEditor] available for `.svg` files. Registered with `order="last"` so the
 * built-in IDEA SVG image viewer stays the default tab on open, while SvgEasy coexists as an
 * additional tab (it is not forced to replace the image viewer).
 *
 * To make SvgEasy the *default* `.svg` editor instead (replacing the image viewer), change the
 * registration to `order="first"`.
 */
class SvgEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = file.extension.equals("svg", ignoreCase = true)

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor = SvgPreviewEditor(project, file)

    override fun getEditorTypeId(): String = "SvgEasy.text.editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
