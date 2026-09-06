package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/** Executes submitted blocks only when [drainOne] is called (manual clock for tests). */
private class QueueExecutor : Executor {
    val queue = mutableListOf<Runnable>()

    override fun execute(command: Runnable) {
        queue.add(command)
    }

    /** Run the next queued block; returns false when the queue is empty. */
    fun drainOne(): Boolean {
        if (queue.isEmpty()) return false
        queue.removeAt(0).run()
        return true
    }
}

class RenderSchedulerTest {
    @Test
    fun `runs the task and publishes the result with its tag`() {
        val worker = QueueExecutor()
        var delivered: Pair<String?, Any?>? = null
        val scheduler =
            RenderScheduler(worker, edt = { it.run() })
        scheduler.submit(RenderScheduler.Slot.CONTENT, "tag-1", { 42 }) { result, tag ->
            delivered = (result as Int?)?.toString() to tag
        }
        assertTrue(worker.drainOne())
        assertEquals("42" to "tag-1" as Any?, delivered)
    }

    @Test
    fun `latest submission wins within a slot`() {
        val worker = QueueExecutor()
        val ran = mutableListOf<Int>()
        val scheduler = RenderScheduler(worker, edt = { it.run() })
        scheduler.submit(RenderScheduler.Slot.CONTENT, 1, { ran.add(1) }) { _, _ -> }
        scheduler.submit(RenderScheduler.Slot.CONTENT, 2, { ran.add(2) }) { _, _ -> }
        assertEquals(1, scheduler.pendingCount())
        assertTrue(worker.drainOne())
        assertEquals(listOf(2), ran)
    }

    @Test
    fun `content drains before layers`() {
        val worker = QueueExecutor()
        val order = mutableListOf<String>()
        val scheduler = RenderScheduler(worker, edt = { it.run() })
        scheduler.submit(RenderScheduler.Slot.LAYERS, null, { order.add("layers") }) { _, _ -> }
        scheduler.submit(RenderScheduler.Slot.CONTENT, null, { order.add("content") }) { _, _ -> }
        assertTrue(worker.drainOne())
        assertEquals(listOf("content", "layers"), order)
    }

    @Test
    fun `cancel drops the pending job`() {
        val worker = QueueExecutor()
        var ran = false
        val scheduler = RenderScheduler(worker, edt = { it.run() })
        scheduler.submit(RenderScheduler.Slot.CONTENT, null, { ran = true }) { _, _ -> }
        scheduler.cancel(RenderScheduler.Slot.CONTENT)
        assertEquals(0, scheduler.pendingCount())
        worker.drainOne()
        assertTrue(!ran)
    }

    @Test
    fun `dispose drops pending jobs and ignores new submissions`() {
        val worker = QueueExecutor()
        var ran = false
        val scheduler = RenderScheduler(worker, edt = { it.run() })
        scheduler.submit(RenderScheduler.Slot.CONTENT, null, { ran = true }) { _, _ -> }
        scheduler.dispose()
        assertEquals(0, scheduler.pendingCount())
        scheduler.submit(RenderScheduler.Slot.CONTENT, null, { ran = true }) { _, _ -> }
        worker.drainOne()
        assertTrue(!ran)
    }

    @Test
    fun `jobs submitted while draining still run`() {
        val worker = QueueExecutor()
        val ran = mutableListOf<Int>()
        val scheduler = RenderScheduler(worker, edt = { it.run() })
        scheduler.submit(RenderScheduler.Slot.CONTENT, null, { ran.add(1) }) { _, _ ->
            scheduler.submit(RenderScheduler.Slot.CONTENT, null, { ran.add(2) }) { _, _ -> }
        }
        assertTrue(worker.drainOne()) // the single drain loop picks up the nested submission
        assertTrue(!worker.drainOne())
        assertEquals(listOf(1, 2), ran)
    }

    /** A worker that runs nothing — proves cancel/dispose need no executor cooperation. */
    private fun deliveredOf(worker: QueueExecutor): Any? = if (worker.queue.isEmpty()) null else Any()
}
