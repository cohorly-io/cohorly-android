package com.cohorly.android.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ThreadReplayWorkerTest {
    @Test
    fun `a task queued before shutdown still runs`() {
        val worker = ThreadReplayWorker("test-worker")
        val gate = CountDownLatch(1)
        val ran = CountDownLatch(1)
        worker.execute { gate.await(5, TimeUnit.SECONDS) }
        worker.execute { ran.countDown() }
        worker.shutdown()
        gate.countDown()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a delayed task never runs after shutdown`() {
        val worker = ThreadReplayWorker("test-worker")
        val ran = AtomicBoolean(false)
        worker.schedule(100) { ran.set(true) }
        worker.shutdown()
        Thread.sleep(300)
        assertFalse(ran.get())
    }

    @Test
    fun `a delayed task runs when the worker stays up`() {
        val worker = ThreadReplayWorker("test-worker")
        val ran = CountDownLatch(1)
        worker.schedule(10) { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        worker.shutdown()
    }

    @Test
    fun `work after shutdown is ignored, never thrown`() {
        val worker = ThreadReplayWorker("test-worker")
        worker.shutdown()
        var ran = 0
        worker.execute { ran++ }
        worker.schedule(0) { ran++ }
        Thread.sleep(50)
        assertEquals(0, ran)
    }
}
