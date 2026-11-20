package com.cohorly.android.core.replay

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A serial executor for replay work. Session replay uses two (see
 * [ReplayController]): one for state, disk and listener notifications, which
 * never does network, and one for uploads. Tests inject manual ones.
 */
interface ReplayExecutor {
    fun execute(task: Runnable)

    fun schedule(delayMs: Long, task: Runnable)
}

/** A daemon thread named [name], started on first use. */
class ThreadReplayExecutor(private val name: String = STATE_THREAD) : ReplayExecutor {
    private val executor: ScheduledExecutorService by lazy {
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
    }

    override fun execute(task: Runnable) {
        executor.execute(task)
    }

    override fun schedule(delayMs: Long, task: Runnable) {
        executor.schedule(task, delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    companion object {
        /** State, chunk writes and session listeners; never network. */
        const val STATE_THREAD = "cohorly-replay"

        /** `POST /replay` and the queue changes its answers cause. */
        const val UPLOAD_THREAD = "cohorly-replay-upload"
    }
}
