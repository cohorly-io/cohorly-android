package com.cohorly.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.cohorly.android.core.DeviceInfoProvider

/**
 * Android implementation of [DeviceInfoProvider], mirroring the default
 * properties the Mixpanel Android SDK stamps on every event.
 *
 * Only properties obtainable WITHOUT permissions the host app may lack are
 * always included. `$wifi` requires ACCESS_NETWORK_STATE and
 * `$bluetooth_enabled` requires BLUETOOTH (< API 31) / BLUETOOTH_CONNECT
 * (API 31+): both are included only when the app already holds the
 * permission, and omitted otherwise. `$radio` is skipped entirely (needs
 * READ_PHONE_STATE on modern Android). Geo properties ($city, $region,
 * mp_country_code) are stamped server-side from the client IP.
 */
class AndroidDeviceInfo(private val context: Context) : DeviceInfoProvider {
    companion object {
        /** SDK version, sent as `$lib_version` on every event. */
        const val LIB_VERSION = "0.5.0"
    }

    /** Properties that cannot change during the process lifetime. */
    private val staticProperties: Map<String, Any?> = buildStaticProperties()

    private val cachedAppVersion: String? = packageInfoOrNull()?.versionName

    override fun defaultProperties(): Map<String, Any?> {
        val props = HashMap(staticProperties)
        wifiConnected()?.let { props["\$wifi"] = it }
        bluetoothEnabled()?.let { props["\$bluetooth_enabled"] = it }
        return props
    }

    override fun appVersion(): String? = cachedAppVersion

    /**
     * Default profile properties mirroring the `$android_*` set mixpanel-android
     * merges into every people update. Only derivable keys are included (app
     * version/code omitted when PackageInfo is unavailable).
     */
    override fun profileProperties(): Map<String, Any?> = cachedProfileProperties

    private val cachedProfileProperties: Map<String, Any?> = buildProfileProperties()

    private fun buildProfileProperties(): Map<String, Any?> {
        val props = LinkedHashMap<String, Any?>()
        props["\$android_lib_version"] = LIB_VERSION
        props["\$android_os"] = "Android"
        props["\$android_os_version"] = Build.VERSION.RELEASE ?: "UNKNOWN"
        props["\$android_manufacturer"] = Build.MANUFACTURER
        props["\$android_brand"] = Build.BRAND
        props["\$android_model"] = Build.MODEL
        packageInfoOrNull()?.let { info ->
            info.versionName?.let { props["\$android_app_version"] = it }
            @Suppress("DEPRECATION")
            props["\$android_app_version_code"] =
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }
        return props
    }

    private fun buildStaticProperties(): Map<String, Any?> {
        val props = LinkedHashMap<String, Any?>()
        props["\$lib_version"] = LIB_VERSION
        props["\$os"] = "Android"
        props["\$os_version"] = Build.VERSION.RELEASE ?: "UNKNOWN"
        props["\$manufacturer"] = Build.MANUFACTURER
        props["\$brand"] = Build.BRAND
        props["\$model"] = Build.MODEL

        val metrics = context.resources.displayMetrics
        props["\$screen_dpi"] = metrics.densityDpi
        props["\$screen_height"] = metrics.heightPixels
        props["\$screen_width"] = metrics.widthPixels

        packageInfoOrNull()?.let { info ->
            props["\$app_version_string"] = info.versionName
            @Suppress("DEPRECATION")
            props["\$app_build_number"] =
                if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }

        val pm = context.packageManager
        props["\$has_nfc"] = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
        props["\$has_telephone"] = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        props["\$bluetooth_version"] = when {
            pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "ble"
            pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) -> "classic"
            else -> "none"
        }

        carrierName()?.let { props["\$carrier"] = it }
        googlePlayServicesAvailable()?.let { props["\$google_play_services"] = it }
        return props
    }

    private fun packageInfoOrNull() = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (_: Exception) {
        null
    }

    /** Carrier display name; needs no permission. Omitted when blank. */
    private fun carrierName(): String? = try {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephony?.networkOperatorName?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /**
     * True when Google Play services is available. Checked via reflection on
     * GoogleApiAvailability so the SDK carries no play-services dependency;
     * omitted (null) when the host app doesn't bundle play-services.
     */
    private fun googlePlayServicesAvailable(): Boolean? = try {
        val clazz = Class.forName("com.google.android.gms.common.GoogleApiAvailability")
        val instance = clazz.getMethod("getInstance").invoke(null)
        val code = clazz
            .getMethod("isGooglePlayServicesAvailable", Context::class.java)
            .invoke(instance, context) as Int
        code == 0 // ConnectionResult.SUCCESS
    } catch (_: Throwable) {
        null
    }

    /** Wifi connectivity; only when the app holds ACCESS_NETWORK_STATE. */
    private fun wifiConnected(): Boolean? {
        if (!hasPermission(Manifest.permission.ACCESS_NETWORK_STATE)) return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            if (Build.VERSION.SDK_INT >= 23) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bluetooth adapter state; only when the app holds BLUETOOTH (< API 31)
     * or BLUETOOTH_CONNECT (API 31+).
     */
    private fun bluetoothEnabled(): Boolean? {
        val permission = if (Build.VERSION.SDK_INT >= 31) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        if (!hasPermission(permission)) return null
        return try {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()?.isEnabled
        } catch (_: Exception) {
            null
        }
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
