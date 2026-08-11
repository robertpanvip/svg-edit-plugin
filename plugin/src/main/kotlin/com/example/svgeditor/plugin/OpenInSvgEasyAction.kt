package com.example.svgeditor.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens the current `.svg` file and focuses its [SvgPreviewEditor] (text + design canvas) tab.
 *
 * The [SvgEditorProvider] already makes SvgEasy available as a tab whenever an `.svg` is opened,
 * so this action is a convenience shortcut that opens the file and selects the SvgEasy editor.
 */
class OpenInSvgEasyAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!isSvg(file)) return
        // Opens the file; the registered SvgEditorProvider surfaces the SvgEasy (text + design
        // canvas) tab alongside the built-in SVG image viewer.
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && isSvg(file)
    }

    private fun isSvg(file: VirtualFile): Boolean = file.extension.equals("svg", ignoreCase = true)
}
