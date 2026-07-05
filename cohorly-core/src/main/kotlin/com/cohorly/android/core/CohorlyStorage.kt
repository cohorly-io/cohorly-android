package com.cohorly.android.core

/**
 * String key-value persistence abstraction. The Android module backs this with
 * SharedPreferences; tests use [InMemoryStorage].
 */
interface CohorlyStorage {
    fun get(key: String): String?

    /** `null` removes the key. */
    fun set(key: String, value: String?)
}

/** In-memory storage for tests and non-Android JVM use. */
class InMemoryStorage : CohorlyStorage {
    private val map = HashMap<String, String>()

    @Synchronized
    override fun get(key: String): String? = map[key]

    @Synchronized
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
