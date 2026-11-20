package com.cohorly.android.replay

/** Runs tasks at once on the calling thread; scheduled ones when [advance] reaches them. */
internal class TestExecutor : ReplayWorker {
    var nowMs = 0L
    private val scheduled = mutableListOf<Pair<Long, Runnable>>()

    override fun execute(task: Runnable) {
        task.run()
    }

    override fun schedule(delayMs: Long, task: Runnable) {
        scheduled.add(nowMs + delayMs to task)
    }

    fun advance(ms: Long) {
        val end = nowMs + ms
        while (true) {
            val next = scheduled.filter { it.first <= end }.minByOrNull { it.first } ?: break
            scheduled.remove(next)
            nowMs = next.first
            next.second.run()
        }
        nowMs = end
    }
}
