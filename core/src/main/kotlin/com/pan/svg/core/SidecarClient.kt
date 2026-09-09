package com.pan.svg.core

import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/** Thrown when a sidecar RPC fails, times out, or returns a malformed reply. */
class SidecarException(message: String) : RuntimeException(message)

data class SidecarInfo(val version: String, val protocol: Int)

/** Pre-rendered drag images: the full frame with the dragged element removed, and the element alone. */
data class SidecarDragImages(
    val background: BufferedImage,
    val ghost: BufferedImage,
    val width: Int,
    val height: Int,
)

/** Result of committing a transform: round-trip SVG, re-rendered frame, and fresh layout elements. */
data class SidecarCommit(
    val svg: String,
    val png: BufferedImage,
    val elements: List<SvgElement>,
    val width: Int,
    val height: Int,
)

/**
 * Client for the Rust `sidecar` helper process, speaking newline-delimited JSON-RPC over stdio.
 *
 * The process is started lazily on first use and restarted transparently if it dies; [open]'s
 * last document is replayed after a restart so editing state survives a crashed sidecar. Pending
 * requests are parked in a swap-able map, so a restart never completes a future issued against
 * the new process with a stale reader thread.
 */
open class SidecarClient(private val command: List<String>) : AutoCloseable {
    private val nextId = AtomicLong(0)
    private val startLock = Any()
    private val writeLock = Any()

    @Volatile private var process: Process? = null
    @Volatile private var writerRef: BufferedWriter? = null
    @Volatile private var pending = ConcurrentHashMap<Long, CompletableFuture<Any?>>()
    @Volatile private var closed = false
    @Volatile private var lastSvg: String? = null

    /** Handshake + one round trip. Overridable so tests can fake the sidecar without a process. */
    protected open fun exchange(
        method: String,
        params: Map<String, Any?>,
        timeoutMs: Long = RPC_TIMEOUT_MS,
    ): Any? {
        check(!closed) { "sidecar client is closed" }
        ensureStarted()
        val writer = writerRef ?: throw SidecarException("sidecar is not running")
        val map = pending
        val id = nextId.incrementAndGet()
        val f = CompletableFuture<Any?>()
        map[id] = f
        val line = Json.write(linkedMapOf("id" to id, "method" to method, "params" to params))
        try {
            synchronized(writeLock) {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            map.remove(id)
            throw SidecarException("failed to write sidecar request: ${e.message}")
        }
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause as? SidecarException) ?: SidecarException(e.cause?.message ?: "sidecar failure")
        } catch (e: TimeoutException) {
            map.remove(id)
            throw SidecarException("sidecar request '$method' timed out")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SidecarException("interrupted while waiting for sidecar")
        }
    }

    fun ping(): SidecarInfo {
        val res = reply(request("ping", emptyMap(), replayOnRestart = false), "ping")
        return SidecarInfo(
            version = res["version"] as? String ?: "",
            protocol = (res["protocol"] as? Number)?.toInt() ?: 0,
        )
    }

    fun open(svg: String): SvgLayout {
        val res = reply(request("open", linkedMapOf("svg" to svg), replayOnRestart = false), "open")
        lastSvg = svg
        return SvgLayout(
            width = (res["width"] as? Number)?.toDouble() ?: 0.0,
            height = (res["height"] as? Number)?.toDouble() ?: 0.0,
            elements = elementsOf(res["elements"]),
        )
    }

    /** Exact path hit test; returns the editor-tree node id, or null when nothing was hit. */
    fun hitTest(x: Double, y: Double, tol: Double): Long? {
        val res =
            request(
                "hitTest",
                linkedMapOf("x" to x, "y" to y, "tol" to tol),
                timeoutMs = HIT_TIMEOUT_MS,
            ) as? Map<*, *> ?: return null
        return (res["nodeId"] as? Number)?.toLong()
    }

    fun startDrag(
        nodeId: Long,
        vw: Int,
        vh: Int,
        scale: Double,
        tx: Double,
        ty: Double,
    ): SidecarDragImages {
        val res =
            reply(
                request(
                    "startDrag",
                    linkedMapOf(
                        "nodeId" to nodeId, "vw" to vw, "vh" to vh,
                        "scale" to scale, "tx" to tx, "ty" to ty,
                    ),
                ),
                "startDrag",
            )
        val bg = res["bgPng"] as? String ?: throw SidecarException("startDrag reply missing bgPng")
        val ghost = res["ghostPng"] as? String ?: throw SidecarException("startDrag reply missing ghostPng")
        return SidecarDragImages(
            background = decodePng(bg),
            ghost = decodePng(ghost),
            width = (res["w"] as? Number)?.toInt() ?: 0,
            height = (res["h"] as? Number)?.toInt() ?: 0,
        )
    }

    /**
     * Group drag layers: the background hides EVERY listed member's subtree and the single
     * ghost keeps all of them (with their ancestor groups), so a multi-selection drag can
     * preview as one unit that moves together.
     */
    fun startDragGroup(
        nodeIds: List<Long>,
        vw: Int,
        vh: Int,
        scale: Double,
        tx: Double,
        ty: Double,
    ): SidecarDragImages {
        val res =
            reply(
                request(
                    "startDragGroup",
                    linkedMapOf(
                        "nodeIds" to nodeIds, "vw" to vw, "vh" to vh,
                        "scale" to scale, "tx" to tx, "ty" to ty,
                    ),
                ),
                "startDragGroup",
            )
        val bg = res["bgPng"] as? String ?: throw SidecarException("startDragGroup reply missing bgPng")
        val ghost = res["ghostPng"] as? String ?: throw SidecarException("startDragGroup reply missing ghostPng")
        return SidecarDragImages(
            background = decodePng(bg),
            ghost = decodePng(ghost),
            width = (res["w"] as? Number)?.toInt() ?: 0,
            height = (res["h"] as? Number)?.toInt() ?: 0,
        )
    }

    fun commit(
        nodeId: Long,
        matrix: List<Double>,
        vw: Int,
        vh: Int,
        scale: Double,
        tx: Double,
        ty: Double,
    ): SidecarCommit {
        val res =
            reply(
                request(
                    "commit",
                    linkedMapOf(
                        "nodeId" to nodeId, "matrix" to matrix, "vw" to vw, "vh" to vh,
                        "scale" to scale, "tx" to tx, "ty" to ty,
                    ),
                ),
                "commit",
            )
        val svg = res["svg"] as? String ?: throw SidecarException("commit reply missing svg")
        val png = res["png"] as? String ?: throw SidecarException("commit reply missing png")
        return SidecarCommit(
            svg = svg,
            png = decodePng(png),
            elements = elementsOf(res["elements"]),
            width = (res["w"] as? Number)?.toInt() ?: 0,
            height = (res["h"] as? Number)?.toInt() ?: 0,
        )
    }

    fun remove(
        nodeId: Long,
        vw: Int,
        vh: Int,
        scale: Double,
        tx: Double,
        ty: Double,
    ): SidecarCommit {
        val res =
            reply(
                request(
                    "remove",
                    linkedMapOf(
                        "nodeId" to nodeId, "vw" to vw, "vh" to vh,
                        "scale" to scale, "tx" to tx, "ty" to ty,
                    ),
                ),
                "remove",
            )
        val svg = res["svg"] as? String ?: throw SidecarException("remove reply missing svg")
        val png = res["png"] as? String ?: throw SidecarException("remove reply missing png")
        return SidecarCommit(
            svg = svg,
            png = decodePng(png),
            elements = elementsOf(res["elements"]),
            width = (res["w"] as? Number)?.toInt() ?: 0,
            height = (res["h"] as? Number)?.toInt() ?: 0,
        )
    }

    fun renderViewport(vw: Int, vh: Int, scale: Double, tx: Double, ty: Double): BufferedImage {
        val res =
            reply(
                request(
                    "renderViewport",
                    linkedMapOf("vw" to vw, "vh" to vh, "scale" to scale, "tx" to tx, "ty" to ty),
                ),
                "renderViewport",
            )
        val png = res["png"] as? String ?: throw SidecarException("renderViewport reply missing png")
        return decodePng(png)
    }

    override fun close() {
        closed = true
        synchronized(startLock) {
            failAll(pending, "sidecar client closed")
            killProcess()
            writerRef = null
        }
    }

    private fun request(
        method: String,
        params: Map<String, Any?>,
        replayOnRestart: Boolean = true,
        timeoutMs: Long = RPC_TIMEOUT_MS,
    ): Any? {
        check(!closed) { "sidecar client is closed" }
        return try {
            exchange(method, params, timeoutMs)
        } catch (e: Exception) {
            restart(replayOnRestart)
            exchange(method, params, timeoutMs)
        }
    }

    private fun ensureStarted() {
        if (isAlive()) return
        synchronized(startLock) {
            if (closed) throw SidecarException("sidecar client is closed")
            if (isAlive()) return
            val proc =
                try {
                    ProcessBuilder(command)
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start()
                } catch (e: Exception) {
                    throw SidecarException("failed to start sidecar: ${e.message}")
                }
            val map = ConcurrentHashMap<Long, CompletableFuture<Any?>>()
            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val thread =
                Thread {
                    while (true) {
                        val line =
                            try {
                                reader.readLine()
                            } catch (e: Exception) {
                                null
                            } ?: break
                        dispatch(line, map)
                    }
                    failAll(map, "sidecar process exited")
                }
            thread.isDaemon = true
            thread.name = "sidecar-reader"
            thread.start()
            process = proc
            pending = map
            writerRef = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            try {
                val res = exchange("ping", emptyMap())
                val m = res as? Map<*, *> ?: throw SidecarException("malformed sidecar ping reply")
                if ((m["protocol"] as? Number)?.toInt() != PROTOCOL) {
                    throw SidecarException("sidecar protocol mismatch: ${m["protocol"]}")
                }
            } catch (e: Exception) {
                killProcess()
                writerRef = null
                throw e
            }
        }
    }

    private fun restart(replay: Boolean) {
        synchronized(startLock) {
            killProcess()
            writerRef = null
            failAll(pending, "sidecar restarted")
            pending = ConcurrentHashMap<Long, CompletableFuture<Any?>>()
            if (replay && lastSvg != null) {
                exchange("open", linkedMapOf("svg" to lastSvg))
            }
        }
    }

    private fun dispatch(
        line: String,
        map: ConcurrentHashMap<Long, CompletableFuture<Any?>>,
    ) {
        val m =
            try {
                Json.parse(line) as? Map<*, *>
            } catch (e: Exception) {
                null
            } ?: return
        val id = (m["id"] as? Number)?.toLong() ?: return
        val f = map.remove(id) ?: return
        val err = m["error"]
        if (err != null) f.completeExceptionally(SidecarException(err.toString())) else f.complete(m["result"])
    }

    private fun failAll(map: ConcurrentHashMap<Long, CompletableFuture<Any?>>, message: String) {
        for (f in map.values) f.completeExceptionally(SidecarException(message))
        map.clear()
    }

    private fun isAlive(): Boolean = process?.isAlive == true

    private fun killProcess() {
        val p = process ?: return
        p.destroy()
        if (!p.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly()
        process = null
    }

    private fun reply(res: Any?, method: String): Map<*, *> =
        res as? Map<*, *> ?: throw SidecarException("malformed $method reply")

    private fun elementsOf(list: Any?): List<SvgElement> {
        val arr = list as? List<*> ?: return emptyList()
        return SvgLayout.parse(
            Json.write(linkedMapOf("width" to 0, "height" to 0, "elements" to arr)),
        ).elements
    }

    private fun decodePng(base64: String): BufferedImage {
        val bytes =
            try {
                Base64.getDecoder().decode(base64)
            } catch (e: IllegalArgumentException) {
                throw SidecarException("sidecar sent a malformed PNG payload")
            }
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw SidecarException("sidecar sent an undecodable PNG")
        return toArgbPre(img)
    }

    private fun toArgbPre(img: BufferedImage): BufferedImage {
        if (img.type == BufferedImage.TYPE_INT_ARGB_PRE) return img
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB_PRE)
        val g = out.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    companion object {
        private const val RPC_TIMEOUT_MS = 30_000L
        private const val HIT_TIMEOUT_MS = 2_000L
        private const val KILL_GRACE_MS = 300L
        private const val PROTOCOL = 1
    }
}
