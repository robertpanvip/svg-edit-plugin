package com.pan.svg.core

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon
import javax.swing.UIManager

/**
 * Semantic vector icons for the interaction tools, drawn in pure Swing so the standalone app
 * toolbar and the IntelliJ plugin toolbar share one source of truth (no IntelliJ dependency).
 *
 * The glyphs are theme-aware: the stroke/fill colour is derived from the toolbar background's
 * luminance, so the buttons stay legible under light and dark LAFs alike. They are deliberately
 * distinct — the Move tool is a four-direction pan arrow, the Box Select tool is a dashed
 * marquee rectangle — so the two mode toggles can never be mistaken for each other.
 */
object EditorIcons {
    /** Logical canvas size of every tool icon (px). */
    const val SIZE = 16

    /** Move tool: four arrows pointing out from the centre (pan/move). */
    fun moveTool(): Icon = vectorIcon { g2, pen ->
        val s = SIZE.toDouble()
        // Arrow heads on all four axes; the arm strokes overlap their bases.
        head(g2, pen, s / 2, 2.2, s / 2 - 2.6, 6.2, s / 2 + 2.6, 6.2)
        head(g2, pen, s / 2, s - 2.2, s / 2 - 2.6, s - 6.2, s / 2 + 2.6, s - 6.2)
        head(g2, pen, 2.2, s / 2, 6.2, s / 2 - 2.6, 6.2, s / 2 + 2.6)
        head(g2, pen, s - 2.2, s / 2, s - 6.2, s / 2 - 2.6, s - 6.2, s / 2 + 2.6)
        stroke(g2, pen, s / 2, 5.6, s / 2, s - 5.6, 1.4)
        stroke(g2, pen, 5.6, s / 2, s - 5.6, s / 2, 1.4)
    }

    /** Box Select tool: a dashed selection rectangle (rubber band). */
    fun boxSelectTool(): Icon = vectorIcon { g2, pen ->
        g2.color = pen
        g2.stroke = BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(3f, 2.5f), 0f)
        g2.drawRect(2, 2, SIZE - 4, SIZE - 4)
    }

    private fun vectorIcon(body: (Graphics2D, Color) -> Unit): Icon =
        object : Icon {
            override fun getIconWidth(): Int = SIZE
            override fun getIconHeight(): Int = SIZE
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val pen = ink(c)
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                    g2.translate(x, y)
                    body(g2, pen)
                } finally {
                    g2.dispose()
                }
            }
        }

    private fun stroke(
        g2: Graphics2D,
        pen: Color,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        width: Double,
    ) {
        g2.color = pen
        g2.stroke = BasicStroke(width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawLine(round(x1), round(y1), round(x2), round(y2))
    }

    /** Filled triangular arrow head. Tip `(tx,ty)`, base from `(b1x,b1y)` to `(b2x,b2y)`. */
    private fun head(
        g2: Graphics2D,
        pen: Color,
        tx: Double,
        ty: Double,
        b1x: Double,
        b1y: Double,
        b2x: Double,
        b2y: Double,
    ) {
        val p = Path2D.Double()
        p.moveTo(tx, ty)
        p.lineTo(b1x, b1y)
        p.lineTo(b2x, b2y)
        p.closePath()
        g2.color = pen
        g2.fill(p)
    }

    private fun round(v: Double): Int = kotlin.math.round(v).toInt()

    /**
     * Toolbar glyph colour: dark ink on a light toolbar, light ink on a dark one. Falls back to
     * the `ToolBar.background` UI colour when no painted component is available (icon previews).
     */
    private fun ink(c: Component?): Color {
        val bg = c?.background ?: UIManager.getColor("ToolBar.background") ?: UIManager.getColor("Panel.background")
        if (bg != null) {
            val lum = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
            return if (lum > 140) Color(0x4A, 0x4A, 0x4A) else Color(0xBC, 0xBF, 0xC9)
        }
        return Color(0x6E, 0x6E, 0x6E)
    }
}
