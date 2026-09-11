package com.pan.svg.core

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/*
 * Modal Swing dialogs for the SVGO feature. They live in `core` (pure Swing, no IntelliJ import)
 * so the plugin and the standalone app can share one wording. Nothing here touches AWT at
 * class-init time — every widget is created inside the called function — so headless test runs
 * can load `core` freely; the dialogs only exist once someone actually shows them.
 */

/**
 * Lets the user choose which SVGO passes run. The passes are listed as checkboxes under their
 * group heading, in the order given; a pass is checked unless it is explicitly disabled in
 * [current] (`current[name] != false`), matching the engine's default-on semantics.
 *
 * Returns a map of EVERY pass name to its new boolean on OK, or `null` when the dialog was
 * cancelled (button or Escape). Returning the full map keeps the persisted settings
 * self-describing; the engine treats a `true` entry exactly like an omitted one.
 */
fun showSvgoSettingsDialog(
    parent: Component?,
    passes: List<SvgoPass>,
    current: Map<String, Boolean>,
): Map<String, Boolean>? {
    val owner = parent?.let { SwingUtilities.getWindowAncestor(it) }
    val dialog = JDialog(owner, "SVGO Settings", Dialog.ModalityType.APPLICATION_MODAL)

    val boxes = LinkedHashMap<String, JCheckBox>()
    val list =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    val grouped = LinkedHashMap<String, MutableList<SvgoPass>>()
    for (pass in passes) grouped.getOrPut(pass.group) { mutableListOf() }.add(pass)
    for ((group, members) in grouped) {
        if (group.isNotEmpty()) {
            list.add(
                JLabel(group).apply {
                    font = font?.deriveFont(Font.BOLD)
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = BorderFactory.createEmptyBorder(8, 2, 2, 2)
                },
            )
        }
        for (pass in members) {
            val box =
                JCheckBox(pass.label, current[pass.name] != false).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            boxes[pass.name] = box
            list.add(box)
        }
    }

    val scroll = JScrollPane(list).apply { preferredSize = Dimension(340, 420) }

    val allButton = JButton("All")
    val noneButton = JButton("None")
    val cancelButton = JButton("Cancel")
    val okButton = JButton("OK")

    var chosen: Map<String, Boolean>? = null
    // "All" doubles as "restore the defaults": SVGO's preset has every catalogue pass on, so
    // clearing the exceptions is the same thing as ticking everything.
    allButton.addActionListener { for (box in boxes.values) box.isSelected = true }
    noneButton.addActionListener { for (box in boxes.values) box.isSelected = false }
    cancelButton.addActionListener { dialog.dispose() }
    okButton.addActionListener {
        val result = LinkedHashMap<String, Boolean>()
        for (pass in passes) result[pass.name] = boxes[pass.name]?.isSelected ?: true
        chosen = result
        dialog.dispose()
    }

    val buttons =
        JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(allButton)
            add(noneButton)
            add(cancelButton)
            add(okButton)
        }

    dialog.layout = BorderLayout()
    dialog.add(scroll, BorderLayout.CENTER)
    dialog.add(buttons, BorderLayout.SOUTH)
    dialog.rootPane.defaultButton = okButton
    // Escape cancels, mirroring the Cancel button; `chosen` stays null in that case.
    dialog.rootPane.registerKeyboardAction(
        { dialog.dispose() },
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
    )
    dialog.pack()
    dialog.setLocationRelativeTo(parent)
    dialog.isVisible = true
    return chosen
}

/**
 * Reports the outcome of one SVGO run: original/optimized sizes, bytes and percentage saved, and
 * how many optimization passes ran. When the file did not get smaller (`savedBytes <= 0`) an
 * extra line says so, so a "saved -12 B" figure is never left unexplained.
 */
fun showSvgoResultDialog(
    parent: Component?,
    result: SvgoResult,
) {
    val percent = String.format(Locale.ROOT, "%.1f", result.savedPercent)
    val message =
        buildString {
            append("<html><body style='width:300px'>")
            append("<b>The SVG was optimized.</b><br><br>")
            append("Original size: ").append(formatBytes(result.beforeBytes)).append("<br>")
            append("Optimized size: ").append(formatBytes(result.afterBytes)).append("<br>")
            append("Bytes saved: ")
                .append(formatBytes(result.savedBytes))
                .append(" (")
                .append(percent)
                .append("%)<br>")
            append("Optimization passes run: ").append(result.passes)
            if (result.savedBytes <= 0) {
                append("<br><br>The optimized file is not smaller than the original.")
            }
            append("</body></html>")
        }
    JOptionPane.showMessageDialog(parent, message, "SVGO", JOptionPane.INFORMATION_MESSAGE)
}
