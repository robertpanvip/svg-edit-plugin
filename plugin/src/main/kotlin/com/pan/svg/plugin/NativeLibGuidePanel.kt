package com.pan.svg.plugin

import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.io.File
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Shown instead of the design canvas when the `svg_easy_sidecar` engine could not be located.
 *
 * The most common cause: the plugin zip was built on a different OS than the one running the
 * IDE (e.g. a zip built on Linux only bundles the Linux sidecar, so on Windows the executable is
 * missing). Instead of failing with a blank panel, this explains what happened and shows every
 * supported way to supply the binary, followed by the exact lookup attempts for diagnosis.
 */
class NativeLibGuidePanel(attempts: String) : JPanel(BorderLayout()) {
    init {
        val sidecarDir = File(SidecarLoader.configDir(), "sidecar")
        val fileName = SidecarLoader.sidecarFileName()
        val prop = SidecarLoader.SIDECAR_PATH_PROPERTY

        val html =
            """
            <html>
            <body style="margin:20px;font-size:${UIUtil.getLabelFont(UIUtil.FontSize.NORMAL).size}pt">
              <h2 style="margin-top:0">SVG rendering engine not available</h2>
              <p>The native engine <b>${fileName}</b> for <b>${SidecarLoader.platformLabel()}</b>
                 could not be found. Most likely this plugin zip was built on another operating
                 system, so it does not contain the executable for this platform.</p>

              <p><b>How to fix (any one of these):</b></p>
              <ol>
                <li>Place a ${fileName} built for this OS at<br>
                    <code>${File(sidecarDir, fileName).absolutePath}</code><br>
                    and restart the IDE.</li>
                <li>Add a VM option to point at the executable directly:<br>
                    <code>-D${prop}=/absolute/path/${fileName}</code><br>
                    (Help &rarr; Edit Custom VM Options, then restart).</li>
                <li>Rebuild the plugin on this OS, which bundles the matching executable:<br>
                    <code>./gradlew :plugin:buildPlugin</code></li>
              </ol>

              <p><b>Lookup attempts of the last load:</b></p>
              <pre style="white-space:pre-wrap">${escape(attempts)}</pre>
            </body>
            </html>
            """.trimIndent()

        val pane =
            JEditorPane("text/html", html).apply {
                isEditable = false
                background = UIUtil.getPanelBackground()
            }
        add(JScrollPane(pane), BorderLayout.CENTER)
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
