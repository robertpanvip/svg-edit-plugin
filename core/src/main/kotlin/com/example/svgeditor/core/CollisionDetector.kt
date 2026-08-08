package com.example.svgeditor.core

/**
 * Collision detection over the `resvg`-derived layout.
 *
 * This is the component that turns "where is the mouse?" into "which element is under it?",
 * and that answers "which elements overlap this rectangle?" — the foundation for hover
 * highlighting, selection and marquee-style selection in the editor panel.
 *
 * Everything here is pure (IntelliJ-free) so it can be unit-tested on a plain JVM.
 */
object CollisionDetector {
    /** Topmost element whose bounding box contains `(px, py)`, or null. */
    fun hitTest(
        layout: SvgLayout,
        px: Double,
        py: Double,
    ): SvgElement? = layout.hitTest(px, py)

    /** All elements whose box intersects the rectangle `(rx, ry, rw, rh)` (marquee hit-test). */
    fun intersecting(
        layout: SvgLayout,
        rx: Double,
        ry: Double,
        rw: Double,
        rh: Double,
    ): List<SvgElement> = layout.intersecting(rx, ry, rw, rh)

    /** Do the two elements' bounding boxes overlap? */
    fun overlaps(
        a: SvgElement,
        b: SvgElement,
    ): Boolean = a.x < b.right && a.right > b.x && a.y < b.bottom && a.bottom > b.y
}
