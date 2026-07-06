# Cohorly Android SDK

Official Android client SDK for [Cohorly](../../README.md), the hosted
Mixpanel-style product analytics platform. Kotlin, minSdk 21, zero third-party
runtime dependencies (JSON via the platform's `org.json`, networking via
`HttpURLConnection`).

The API mirrors the Mixpanel Android SDK, so migrating is mostly a
find-and-replace of `MixpanelAPI` -> `Cohorly` plus an `apiHost` argument.

## Installation

**JitPack** (recommended - built from [cohorly-io/cohorly-android](https://github.com/cohorly-io/cohorly-android)):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.cohorly-io.cohorly-android:cohorly-android:v0.1.0")
}
```

**Maven local** (development):

```bash
cd sdks/android
./gradlew publishToMavenLocal
```

```kotlin
dependencies {
    implementation("com.cohorly:cohorly-android:0.1.0") // mavenLocal()
}
```

The host app must declare the `INTERNET` permission (virtually every app
already does):

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Quickstart

```kotlin
import com.cohorly.android.Cohorly
import org.json.JSONObject

// Initialize once, e.g. in Application.onCreate(). One instance per project token.
// apiHost defaults to the hosted Cohorly API; pass a third argument to target a
// different deployment.
val cohorly = Cohorly.getInstance(context, "YOUR_PROJECT_TOKEN")

// Track events
cohorly.track("Signed Up")
cohorly.track("Plan Selected", JSONObject().put("plan", "pro"))
cohorly.track("Plan Selected", mapOf("plan" to "pro")) // Map overload

// Identify (links the anonymous id via /alias, like the Cohorly JS SDKs)
cohorly.identify("user-123")

// Super properties: merged into every subsequent event
cohorly.registerSuperProperties(JSONObject().put("ab_test", "variant_b"))
cohorly.registerSuperPropertiesOnce(mapOf("first_referrer" to "newsletter"))
cohorly.unregisterSuperProperty("ab_test")
cohorly.clearSuperProperties()

// User profiles (Cohorly /engage)
cohorly.people.set("name", "Ada")
cohorly.people.setOnce(mapOf("signup_date" to "2026-01-01"))
cohorly.people.increment("logins", 1.0)
cohorly.people.unset("legacy_prop")
cohorly.people.deleteUser()

// Lifecycle
cohorly.flush()          // send now (SDK also auto-flushes every 5s / 20 events)
cohorly.reset()          // on logout: new anonymous id, clears state
cohorly.optOutTracking() // GDPR opt-out; optInTracking() to resume
```

Automatic events are opt-in:

```kotlin
val cohorly = Cohorly.getInstance(context, token, apiHost, trackAutomaticEvents = true)
// or full config:
val cohorly = Cohorly.getInstance(
    context, token, apiHost,
    CohorlyConfig(
        flushIntervalMs = 5_000,   // auto-flush interval (mixpanel uses 60s; Cohorly SDKs use 5s)
        batchSize = 20,            // events per request (server cap 500)
        maxQueueSize = 1_000,      // offline queue cap, oldest dropped
        maxRetryDelayMs = 600_000, // retry backoff ceiling
        trackAutomaticEvents = true,
    ),
)
```

## Default properties

Stamped on every event (merge order: device defaults < super properties <
per-call properties < reserved):

| Property | Value |
| --- | --- |
| `distinct_id` | persistent anonymous uuid until `identify()` (reserved) |
| `time` | unix ms (reserved) |
| `$insert_id` | per-event uuid for dedup (reserved) |
| `$lib` | `"android"` (reserved) |
| `token` | project token (reserved) |
| `$lib_version` | SDK version |
| `$os` | `"Android"` |
| `$os_version` | `Build.VERSION.RELEASE` |
| `$manufacturer` / `$brand` / `$model` | `Build.*` |
| `$screen_dpi` / `$screen_height` / `$screen_width` | `DisplayMetrics` |
| `$app_version_string` / `$app_build_number` | `PackageInfo` |
| `$carrier` | carrier name (no permission needed; omitted when blank) |
| `$has_nfc` / `$has_telephone` | `PackageManager` feature flags |
| `$bluetooth_version` | `"ble"` / `"classic"` / `"none"` |
| `$google_play_services` | play-services availability (reflection; omitted when the app doesn't bundle play-services) |
| `$wifi` | only when the app holds `ACCESS_NETWORK_STATE` |
| `$bluetooth_enabled` | only when the app holds `BLUETOOTH` (< API 31) / `BLUETOOTH_CONNECT` (31+) |

Not collected: `$radio` (requires `READ_PHONE_STATE` on modern Android). Geo
properties (`$city`, `$region`, `mp_country_code`) are derived server-side
from the client IP.

## Automatic events

With `trackAutomaticEvents = true` (default false, matching the Cohorly iOS
and React Native SDKs):

- `$ae_first_open` - once ever, plus profile `$ae_first_app_open_date`.
- `$ae_updated` - when the app version changes, prop `$ae_updated_version`.
- `$ae_session` - on app background when the foreground session lasted >= 10s,
  prop `$ae_session_length` (seconds), plus profile increment
  `$ae_total_app_sessions`.

Foreground/background detection uses `Application.ActivityLifecycleCallbacks`
(no androidx dependency); configuration changes don't split sessions.
`$ae_crashed` is not implemented (parity with the other Cohorly SDKs).

## Offline queue and retry behavior

Events persist in SharedPreferences (JSON) so they survive process death -
the iOS SDK uses UserDefaults, React Native uses AsyncStorage;
mixpanel-android uses SQLite, but a 1000-event cap doesn't need it. The queue
drops the OLDEST events past 1000.

Retry contract (shared across all Cohorly SDKs):

- **429 / 5xx / network error**: batch is kept and retried with exponential
  backoff - base 2s, doubling, capped at 10 min, +/-20% jitter. A `Retry-After`
  header on 429 overrides the computed delay (still capped).
- **413**: the effective batch size is halved (floor 1); a single event that
  alone exceeds the 1 MB body cap is dropped to avoid a retry livelock.
- **400**: the batch is permanently rejected and dropped.
- **401** (invalid token): queue kept, backoff at max delay.
- A manual `flush()` bypasses the backoff window.

## Divergences from mixpanel-android

- `apiHost` is optional; it defaults to the hosted Cohorly API (no mixpanel ingestion URL).
- Flush interval defaults to 5s (mixpanel: 60s) - matches the Cohorly siblings.
- `identify()` sends `/alias` linking the anonymous id when still anonymous
  (mixpanel-android's identify doesn't alias; the Cohorly JS core does).
- Persistence is SharedPreferences-JSON, not SQLite.
- People API has no `append` / `union` / `remove` / `merge` (no server-side
  list ops), and `optOutTracking()` does not reset the distinct id.
- No `timeEvent()`, no `$ae_crashed`, no in-app messaging/A-B remnants.

## Project layout / tests

- `cohorly-core/` - pure-JVM Kotlin module: queue, batching, retry, identity,
  props merging, automatic-event logic. `./gradlew :cohorly-core:test` runs the
  full JUnit suite (no emulator, no Android SDK needed).
- `cohorly/` - `com.android.library` glue: `Cohorly.getInstance`,
  SharedPreferences storage, device info, lifecycle watcher.

```bash
./gradlew test              # JVM unit tests
./gradlew :cohorly:assembleRelease  # build the AAR (requires Android SDK)
```
