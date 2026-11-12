package com.cohorly.android.core.crash

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsTest {
    private fun trace(depth: Int, tag: String) =
        Array(depth) { i -> StackTraceElement("com.example.$tag", "m$i", "F.kt", i + 1) }

    @Test
    fun `uncaught keeps 20 threads and the last 100 frames of every stack`() {
        val store = MemoryCrashStore().apply { putConfig(enabledConfig()) }
        val dir = tempDir()
        val threads = (1..30).associate { Thread("t$it") to trace(150, "T$it") }
        val error = IllegalStateException("deep").apply { stackTrace = trace(150, "Crash") }
        val slot = FakeSlot(RecordingHandler())
        newReporter(store, dir, RecordingCrashTransport.offline(), slot = slot, threadDump = { threads }).start(null)

        slot.current!!.uncaughtException(Thread.currentThread(), error)

        val props = PendingReportStore(dir).list().single().event.props()
        val frames = props.getJSONArray("\$exception_list").getJSONObject(0).frames().objects()
        assertEquals(CrashLimits.MAX_FRAMES, frames.size)
        // stackTrace[0] is where it crashed: it must be the last frame kept;
        // the oldest calls (stackTrace[100..149]) are the ones dropped.
        assertEquals("m0", frames.last().getString("function"))
        assertEquals("m99", frames.first().getString("function"))
        val sentThreads = props.getJSONArray("\$exception_threads").objects()
        assertEquals(CrashLimits.MAX_THREADS, sentThreads.size)
        sentThreads.forEach { assertEquals(CrashLimits.MAX_FRAMES, it.frames().length()) }
    }

    @Test
    fun `ANR traces are capped the same way`() {
        val text = buildString {
            for (t in 0 until 30) {
                append("\"${if (t == 0) "main" else "worker-$t"}\" prio=5 tid=${t + 1} Waiting\n")
                for (f in 0 until 150) append("  at com.example.C$t.m$f(C.kt:$f)\n")
                append("\n")
            }
        }
        val record = ExitRecord(
            pid = 1,
            timestamp = 2,
            reason = ExitRecord.REASON_ANR,
            importance = ExitRecord.IMPORTANCE_FOREGROUND,
            description = null,
            processName = TEST_PACKAGE,
            rssKb = 0,
            pssKb = 0,
            processStateSummary = null,
            readTrace = { text.toByteArray() },
        )

        val event = ExitInfoProcessor(MemoryCrashStore(), PendingReportStore(tempDir()), TEST_APP)
            .toEvent(record, TEST_DEVICE_ID)!!

        val frames = event.firstException().frames().objects()
        assertEquals(CrashLimits.MAX_FRAMES, frames.size)
        assertEquals("m0", frames.last().getString("function"))
        val threads = event.props().getJSONArray("\$exception_threads").objects()
        assertEquals(CrashLimits.MAX_THREADS, threads.size)
        threads.forEach { assertEquals(CrashLimits.MAX_FRAMES, it.frames().length()) }
    }

    @Test
    fun `native tombstones are capped the same way`() {
        val tombstone = CrashTestSupport.Proto().varint(6, 100)
            .message(10, CrashTestSupport.Proto().string(2, "SIGSEGV").string(4, "SEGV_MAPERR"))
        for (t in 100L until 130L) {
            val thread = CrashTestSupport.Proto().varint(1, t).string(2, "thread-$t")
            // Innermost frame first, as in a tombstone.
            for (f in 0L until 150L) {
                thread.message(
                    4,
                    CrashTestSupport.Proto().varint(1, 0x1000 + f).string(6, "/system/lib64/libfoo.so")
                        .string(4, "fn$f").string(8, "ABCDEF"),
                )
            }
            tombstone.message(16, CrashTestSupport.Proto().varint(1, t).message(2, thread))
        }
        val bytes = tombstone.toByteArray()
        val record = ExitRecord(
            pid = 1,
            timestamp = 2,
            reason = ExitRecord.REASON_CRASH_NATIVE,
            importance = ExitRecord.IMPORTANCE_FOREGROUND,
            description = null,
            processName = TEST_PACKAGE,
            rssKb = 0,
            pssKb = 0,
            processStateSummary = null,
            readTrace = { bytes },
        )

        val event = ExitInfoProcessor(MemoryCrashStore(), PendingReportStore(tempDir()), TEST_APP)
            .toEvent(record, TEST_DEVICE_ID)!!

        val entry = event.firstException()
        assertEquals("SIGSEGV", entry.getString("type"))
        assertEquals("SEGV_MAPERR", entry.getString("value"))
        val frames = entry.frames().objects()
        assertEquals(CrashLimits.MAX_FRAMES, frames.size)
        assertEquals("fn0", frames.last().getString("function"))
        assertEquals("0x0000000000001000", frames.last().getString("instruction_addr"))
        assertEquals("abcdef", frames.last().getString("debug_id"))
        assertEquals("libfoo.so", frames.last().getString("module"))
        val threads = event.props().getJSONArray("\$exception_threads").objects()
        assertEquals(CrashLimits.MAX_THREADS, threads.size)
        threads.forEach { assertEquals(CrashLimits.MAX_FRAMES, it.frames().length()) }
    }
}
