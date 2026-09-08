package com.pan.svg.core

import java.util.concurrent.Executor
import javax.swing.SwingUtilities

/**
 * Off-EDT render queue for the editor panel (LeaferJS-style "requestRender" model).
 *
 * The panel never rasterizes on the EDT: it *submits* render intents and paints whatever
 * bitmap is currently available. The scheduler keeps at most one pending job per [Slot]
 * (latest-wins, so a burst of wheel ticks collapses into a single render) and drains the
 * slots on a worker [Executor], publishing results back through `edt` (the EDT by default).
 *
 * Results may arrive after the view moved on (zoom/pan/edit). The scheduler therefore hands
 * the caller an opaque `tag` — an immutable snapshot of the request — and the caller drops
 * anything whose tag no longer matches the live state.
 *
 * Testability: pass a direct executor (`{ it.run() }`) plus a direct `edt` for fully
 * synchronous, deterministic behaviour; production passes a single background thread and
 * `SwingUtilities::invokeLater`.
 */
class RenderScheduler(
    private val worker: Executor,
    private val edt: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
) {
    /** Render slots. CONTENT (the full scene) drains before LAYERS (bg/fg for drag preview). */
    enum class Slot { CONTENT, LAYERS }

    private class Job(
        val slot: Slot,
        val tag: Any?,
        val task: () -> Any?,
        val onDone: (Any?, Any?) -> Unit,
    )

    private val lock = Any()
    private val pending = arrayOfNulls<Job>(Slot.entries.size)
    private var draining = false

    @Volatile
    private var disposed = false

    /**
     * Queue (or replace) the pending job for [slot]. Repeated submissions collapse: only the
     * most recent [task]/[tag]/[onDone] per slot survives. The result of [task] is delivered
     * to [onDone] on the `edt` thread together with [tag].
     */
    fun <T> submit(
        slot: Slot,
        tag: Any?,
        task: () -> T,
        onDone: (result: T?, tag: Any?) -> Unit,
    ) {
        if (disposed) return
        synchronized(lock) {
            pending[slot.ordinal] = Job(slot, tag, task) { result, t -> onDone(result as T?, t) }
        }
        pump()
    }

    /** Drop the pending job for [slot] (a no-op if it is already running). */
    fun cancel(slot: Slot) {
        synchronized(lock) { pending[slot.ordinal] = null }
    }

    /** Number of queued (not yet running) jobs — exposed for tests. */
    fun pendingCount(): Int = synchronized(lock) { pending.count { it != null } }

    /** Stop the scheduler: queued jobs are dropped, new submissions are ignored. */
    fun dispose() {
        disposed = true
        synchronized(lock) {
            pending.fill(null)
        }
    }

    private fun pump() {
        synchronized(lock) {
            if (draining || disposed) return
            if (pending[0] == null && pending[1] == null) return
            draining = true
        }
        worker.execute { drain() }
    }

    /** Worker-thread loop: take the highest-priority pending job, run it, publish, repeat. */
    private fun drain() {
        while (true) {
            val job =
                synchronized(lock) {
                    val j = pending[Slot.CONTENT.ordinal] ?: pending[Slot.LAYERS.ordinal]
                    if (j != null) {
                        pending[j.slot.ordinal] = null
                    } else {
                        draining = false
                    }
                    j
                } ?: return
            val result =
                try {
                    job.task()
                } catch (_: Throwable) {
                    null
                }
            if (disposed) return
            edt { job.onDone(result, job.tag) }
        }
    }
}
