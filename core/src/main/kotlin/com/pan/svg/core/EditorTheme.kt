package com.pan.svg.core

import java.awt.BasicStroke
import java.awt.Color

/**
 * LeaferJS-flavoured visual language for the editor canvas.
 *
 * One place for every accent/overlay colour and metric so the selection UI, hover highlight,
 * snap guides and marquee share the same look, and hosts (IDEA plugin vs standalone app) can
 * re-tint the editor without touching interaction code.
 */
object EditorTheme {
    /** Primary accent (LeaferJS violet). Selection outline, handles, marquee. */
    val ACCENT = Color(0x83, 0x6D, 0xFF)

    /** Hover highlight: the accent at reduced alpha. */
    val ACCENT_HOVER = Color(0x83, 0x6D, 0xFF, 120)

    /** Semi-transparent marquee fill (dashed accent border drawn on top). */
    val MARQUEE_FILL = Color(0x83, 0x6D, 0xFF, 22)

    /** Control-point / rotate-handle fill. */
    val HANDLE_FILL = Color.WHITE

    /** Alignment snap guides (red-pink, Figma-like). */
    val SNAP = Color(0xFF, 0x3B, 0x5C)

    /** Selection outline / handle stroke width (px). */
    const val STROKE = 1.5f

    /** Control point size (px). */
    const val HANDLE = 9

    /** Rotate handle: distance above the box top and grip radius (px). */
    const val ROTATE_OFFSET = 24
    const val ROTATE_R = 5

    /** Hit tolerance for handles (px). */
    const val HANDLE_TOLERANCE = 6.0

    /** Dashed stroke used by the marquee rectangle. */
    fun marqueeStroke(): BasicStroke =
        BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f, floatArrayOf(4f, 4f), 0f)
}
