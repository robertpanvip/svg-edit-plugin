package com.example.svgeditor.plugin

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Editor that shows the SVG source (a standard [TextEditor], left) side-by-side with the
 * interactive design canvas ([SvgPreviewPanel], right) in a single tab. Opening an `.svg` file
 * normally shows the built-in image viewer; this editor is opened explicitly through
 * [OpenInSvgEasyAction].
 */
class SvgPreviewEditor(
    project: Project,
    file: VirtualFile,
) : TextEditorWithPreview(
        TextEditorProvider.getInstance().createEditor(project, file) as TextEditor,
        SvgPreviewPanel(project, file),
        "SvgEasy",
    )
