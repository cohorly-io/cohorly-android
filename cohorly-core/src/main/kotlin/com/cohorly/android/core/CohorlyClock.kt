package com.cohorly.android.core

/** Time source abstraction so backoff/session math is deterministic in tests. */
interface CohorlyClock {
    fun nowMillis(): Long
}

/** Default clock backed by the system wall clock. */
class SystemClock : CohorlyClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
