package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserIconDiagnosticTest {
    private lateinit var bridge: ResvgBridge

    private val iconSvg = """
        <svg xmlns="http://www.w3.org/2000/svg" fill="currentColor" class="icon" overflow="hidden" style="width:1em;height:1em;vertical-align:middle" viewBox="0 0 1024 1024">
            <path fill="#666" d="M642.133 981.333c-19.2 0-38.4-8.533-55.466-25.6C563.2 932.267 537.6 919.467 512 921.6c-23.467 0-44.8 10.667-59.733 27.733-19.2 19.2-40.534 29.867-64 29.867h-6.4c-4.267 0-8.534 0-14.934-2.133l-17.066-6.4c-55.467-21.334-104.534-51.2-145.067-91.734l-4.267-4.266c-23.466-21.334-29.866-55.467-17.066-85.334 6.4-21.333 4.266-49.066-10.667-70.4-14.933-25.6-32-40.533-55.467-46.933l-6.4-2.133c-32-8.534-53.333-34.134-59.733-66.134l-2.133-10.666c-4.267-29.867-6.4-59.734-6.4-81.067 0-32 2.133-59.733 8.533-89.6v-4.267l4.267-10.666C64 386.133 76.8 369.067 96 360.533l8.533-4.266c4.267-2.134 6.4-2.134 10.667-4.267 25.6-8.533 46.933-23.467 57.6-42.667 12.8-23.466 14.933-51.2 8.533-81.066L179.2 224c-8.533-34.133 4.267-68.267 29.867-87.467 38.4-34.133 87.466-64 142.933-87.466h2.133c19.2-6.4 38.4-6.4 53.334-2.134 8.533 4.267 17.066 8.534 25.6 14.934l4.266 4.266c23.467 25.6 51.2 36.267 78.934 36.267 21.333-2.133 42.666-10.667 55.466-27.733L576 70.4c25.6-25.6 57.6-34.133 87.467-21.333 55.466 19.2 108.8 51.2 151.466 93.866 25.6 21.334 34.134 59.734 21.334 91.734-6.4 21.333-4.267 49.066 10.666 70.4 14.934 25.6 32 40.533 55.467 46.933l6.4 2.133c32 8.534 53.333 34.134 59.733 66.134 6.4 29.866 8.534 57.6 10.667 89.6V512c0 34.133-2.133 61.867-8.533 91.733v14.934c-8.534 21.333-21.334 36.266-38.4 46.933-2.134 2.133-6.4 4.267-8.534 4.267l-6.4 2.133-6.4 2.133c-25.6 8.534-46.933 23.467-57.6 42.667-12.8 23.467-17.066 51.2-8.533 81.067 10.667 32-2.133 72.533-27.733 89.6-40.534 34.133-87.467 64-145.067 87.466-4.267 2.134-8.533 4.267-14.933 4.267-6.4 2.133-10.667 2.133-14.934 2.133M512 857.6c42.667 0 85.333 19.2 119.467 53.333 4.266 6.4 8.533 6.4 10.666 6.4h4.267C697.6 896 740.267 870.4 774.4 838.4l4.267-4.267c4.266-2.133 8.533-10.666 4.266-17.066v-2.134c-10.666-46.933-6.4-89.6 14.934-128 19.2-34.133 53.333-61.866 96-74.666l4.266-2.134c4.267-2.133 8.534-6.4 12.8-14.933v-4.267c4.267-25.6 6.4-51.2 6.4-78.933-2.133-27.733-4.266-55.467-8.533-78.933V428.8c-2.133-10.667-6.4-12.8-10.667-14.933l-6.4-2.134q-63.999-15.999-96-76.8C774.4 296.533 768 249.6 780.8 211.2l2.133-4.267c2.134-6.4 0-14.933-4.266-19.2l-2.134-2.133c-36.266-36.267-81.066-64-128-81.067l-6.4 2.134c-6.4-2.134-10.666 2.133-14.933 6.4l-2.133 2.133c-23.467 29.867-61.867 49.067-102.4 51.2-46.934 2.133-91.734-17.067-128-53.333l-2.134-2.134s-2.133-2.133-4.266-2.133h-12.8c-49.067 21.333-91.734 46.933-125.867 76.8l-2.133 2.133c-6.4 4.267-8.534 10.667-6.4 17.067l2.133 4.267c10.667 46.933 6.4 89.6-14.933 128-19.2 34.133-53.334 61.866-96 74.666L128 413.867c-2.133 2.133-8.533 6.4-12.8 14.933v4.267c-6.4 27.733-8.533 51.2-8.533 78.933 0 17.067 0 44.8 4.266 68.267l2.134 12.8c2.133 10.666 6.4 12.8 10.666 14.933l6.4 2.133c42.667 10.667 72.534 36.267 96 76.8 23.467 38.4 27.734 85.334 14.934 123.734l-2.134 4.266c-2.133 6.4-2.133 12.8 2.134 17.067l4.266 4.267q51.2 51.2 121.6 76.8l10.667 4.266h4.267c4.266 0 10.666-4.266 17.066-8.533 25.6-29.867 64-49.067 106.667-49.067 4.267-2.133 6.4-2.133 6.4-2.133m134.4 59.733" transform="translate(-5.641873 2.820937)"/>
            <path fill="#666" d="M512 341.333C416 341.333 341.333 416 341.333 512S416 682.667 512 682.667c93.867 0 170.667-76.8 170.667-170.667S605.867 341.333 512 341.333m0 268.8c-55.467 0-98.133-42.666-98.133-98.133s42.666-98.133 98.133-98.133 98.133 42.666 98.133 98.133-42.666 98.133-98.133 98.133" transform="translate(-62.060606 9.873278)"/>
        </svg>
    """.trimIndent()

    @BeforeAll
    private fun loadNative() {
        val lib = findNativeLibrary()
        Assumptions.assumeTrue(lib != null, "native lib not built")
        bridge = ResvgBridge.load(lib!!.toString())
    }

    @Test
    fun `dump layout of the user icon`() {
        val json = bridge.layoutJson(iconSvg)
        println("LAYOUT_JSON >>>" + json)
        val layout = SvgLayout.parse(json)
        println("canvas = ${layout.width} x ${layout.height}  elements=${layout.elements.size}")
        for (el in layout.elements) {
            println("  el id='${el.id}' kind=${el.kind} x=${el.x} y=${el.y} w=${el.width} h=${el.height} tf=${el.transform.joinToString()}")
        }
        // hit middle of canvas and around inner circle
        println("hit(512,512)=" + CollisionDetector.hitTest(layout, 512.0, 512.0)?.id)
        println("hit(512,522)=" + CollisionDetector.hitTest(layout, 512.0, 522.0)?.id)
        println("hit(450,522)=" + CollisionDetector.hitTest(layout, 450.0, 522.0)?.id)
        assertTrue(layout.elements.isNotEmpty())
    }

    private fun nativeLibName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "resvg_bridge.dll"
            os.contains("mac") || os.contains("darwin") -> "libresvg_bridge.dylib"
            else -> "libresvg_bridge.so"
        }
    }

    private fun findNativeLibrary(): Path? {
        val base = Paths.get(System.getProperty("user.dir"), "..", "native", "resvg_bridge", "target")
        val name = nativeLibName()
        for (profile in listOf("debug", "release")) {
            val p = base.resolve(profile).resolve(name)
            if (Files.exists(p)) return p
        }
        return null
    }
}
