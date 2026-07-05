package com.cohorly.android.core

/**
 * Supplies device/app default properties merged into every event (lowest
 * precedence - super props and per-call props override them). The Android
 * module provides `AndroidDeviceInfo`; tests inject a stub.
 */
interface DeviceInfoProvider {
    /**
     * Default properties, e.g. `$lib_version`, `$os`, `$os_version`,
     * `$manufacturer`, `$brand`, `$model`, `$screen_dpi`, ...
     * `null` values are skipped.
     */
    fun defaultProperties(): Map<String, Any?>

    /** Host app version, used for $ae_updated detection. */
    fun appVersion(): String?
}

/** Provider with no device info (plain JVM environments and tests). */
class EmptyDeviceInfo : DeviceInfoProvider {
    override fun defaultProperties(): Map<String, Any?> = emptyMap()
    override fun appVersion(): String? = null
}
