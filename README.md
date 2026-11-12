# Cohorly Android SDK

Official Android client SDK for [Cohorly](../../README.md), the hosted
Mixpanel-style product analytics platform. Kotlin, minSdk 21, zero third-party
runtime dependencies (JSON via the platform's `org.json`, networking via
`HttpURLConnection`).

**Documentation:** [Android SDK reference](https://cohorly-docs.velloalabs.com/sdks/android) · [Quickstart](https://cohorly-docs.velloalabs.com/quickstart) · [HTTP API](https://cohorly-docs.velloalabs.com/api/ingestion)

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
    implementation("com.github.cohorly-io.cohorly-android:cohorly-android:v0.3.0")
}
```

**Maven local** (development):

```bash
cd sdks/android
./gradlew publishToMavenLocal
```

```kotlin
dependencies {
    implementation("com.cohorly:cohorly-android:0.3.0") // mavenLocal()
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

// User profiles (Cohorly /engage). set/setOnce also merge default $android_*
// profile properties (your keys win).
cohorly.people.set("name", "Ada")
cohorly.people.setOnce(mapOf("signup_date" to "2026-01-01"))
cohorly.people.increment("logins", 1.0)
// unset/deleteUser are destructive and refused by the server on the public
// project token alone (they need an org-owner or superadmin Authorization
// credential this SDK does not send) - server-side no-ops here; remove
// profile data from the dashboard or the admin privacy API instead.
cohorly.people.unset("legacy_prop")
cohorly.people.deleteUser()

// Timed events: measure duration between timeEvent() and the next track()
cohorly.timeEvent("Image Upload")
// ... work happens ...
cohorly.track("Image Upload") // carries $duration (seconds, 3 decimals)
cohorly.clearTimedEvent("Image Upload") // cancel a pending timer
cohorly.clearTimedEvents()              // cancel all pending timers

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
| `$device_id` | stable per-device uuid; survives `identify()` (reserved) |
| `$user_id` | the distinct id, stamped only once identified (reserved) |
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

### Default profile properties

Every `people.set` / `people.setOnce` also merges these `$android_*` defaults
(mirroring mixpanel-android); user-supplied keys win. `people.increment` /
`unset` / `deleteUser` are not affected.

| Property | Value |
| --- | --- |
| `$android_lib_version` | SDK version |
| `$android_os` | `"Android"` |
| `$android_os_version` | `Build.VERSION.RELEASE` |
| `$android_app_version` | `PackageInfo.versionName` (omitted when unavailable) |
| `$android_app_version_code` | `PackageInfo.versionCode` (omitted when unavailable) |
| `$android_manufacturer` / `$android_brand` / `$android_model` | `Build.*` |

## Automatic events

With `trackAutomaticEvents = true` (default false, matching the Cohorly iOS
and React Native SDKs):

- `$ae_first_open` - once ever, plus profile `$ae_first_app_open_date`.
- `$ae_updated` - when the app version changes, prop `$ae_updated_version`.
- `$ae_session` - on app background when the foreground session lasted >= 10s,
  prop `$ae_session_length` (seconds), plus a single profile increment bumping
  `$ae_total_app_sessions` (by 1) and `$ae_total_app_session_length` (by the
  session's seconds).

Foreground/background detection uses `Application.ActivityLifecycleCallbacks`
(no androidx dependency); configuration changes don't split sessions.
`$ae_crashed` is not implemented (parity with the other Cohorly SDKs).

## Feature flags

Flags are evaluated on `getInstance` (opt out with
`CohorlyConfig(loadFeatureFlags = false)`), on `identify()` and on `reset()`,
always on the worker thread, and persisted to storage scoped by distinct id;
reads never hit the network.

How reads resolve against the persisted cache is controlled by
`CohorlyConfig.flagPersistence` (default
`FlagPersistenceMode.persistenceUntilNetworkSuccess`, the same three modes the
other Cohorly SDKs and Mixpanel's `variantLookupPolicy` offer):

- `networkOnly` - never reads nor writes the cache on the serving path
  (identity changes still remove it - that is hygiene, not serving); flags
  come only from network responses this session, so a failed evaluation
  serves the fallback.
- `networkFirst` - prefers the network, but because reads are synchronous a
  valid cache (inside the TTL) is served immediately like
  `persistenceUntilNetworkSuccess`. Strict about freshness: a cache past
  the TTL is dropped, never served.
- `persistenceUntilNetworkSuccess` - serves a valid cache immediately, so
  startup doesn't flicker; the first network success takes over and
  re-persists. A failed evaluation keeps the cached values serving. Trades
  freshness for offline availability: an expired cache still serves while
  inside `CohorlyCore.FLAG_CACHE_MAX_STALE_MS` (7 days).

The cache is written with a `stored_at` timestamp on every successful
evaluation and expires after `CohorlyConfig.flagCacheTtlMs` (default 24h - the
Mixpanel reference default, shared with the sibling SDKs). A legacy cache
with no `stored_at` cannot be dated, so it is served indefinitely rather
than expired - the graceful-upgrade path preserving the pre-TTL behavior -
until the next successful reload rewrites it in the stamped format.
`identify()` and `reset()` clear the previous identity's flags
(memory and disk) before fetching the new identity's, so flag values never
leak across users.

- `isFeatureEnabled(key, fallback = false): Boolean`
- `getFeatureFlag(key, fallback = false): Any` - the variant `String` when the
  flag serves one, else its enabled `Boolean`; `fallback` when the flag is
  unknown or unloaded. A read that resolves to `fallback` reports no exposure.
- `getFeatureFlagDetails(key, fallback = false): FlagDetails` - the value plus
  `enabled`, `payload` and `source` (`FlagSource.network` / `.persistence` /
  `.fallback`) and, for persistence-served values, `persistedAt` (epoch ms)
  and `ttlMs`. On a fallback result `enabled` is the fallback's truthiness
  and `payload` is null.
- `getFeatureFlagPayload(key): Any?`
- `getFeatureFlags(): Map<String, FlagResult>`
- `reloadFeatureFlags()` - re-evaluate for the current user (async, worker thread).
- `onFeatureFlags(listener): () -> Unit` - fires after every successful reload,
  when `identify()`/`reset()` clear the served set (with an empty map), and
  immediately when flags are already loaded; returns unsubscribe.

Reads of known flags emit `$feature_flag_called` once per (key, value) per
identity, stamped with `$feature_flag_source` (`"network"` or `"persistence"`)
plus `$feature_flag_persisted_at` and `$feature_flag_ttl_ms` for persistence
reads; the same value served first from persistence and then from the network
is reported exactly once.

## Crash reporting

Off by default. When on, the SDK reports, as `$exception` events
([ADR-0020](../../docs/adr/0020-native-crashes-kscrash-and-next-launch-send.md),
wire format in [docs/NATIVE-SYMBOLICATION.md](../../docs/NATIVE-SYMBOLICATION.md)):

| What | API | Event |
| --- | --- | --- |
| Uncaught Java/Kotlin exception | all | mechanism `uncaught_exception`, level `fatal`, cause chain + other threads |
| ANR that killed the app | 30+ | type `ANR`, mechanism `anr`, level `fatal`, main thread stack |
| Native crash (NDK signal) | 30+ | type = signal (`SIGSEGV`), mechanism `exit_info`, level `fatal`, tombstone frames |
| Low-memory kill in the foreground | 30+ | type `LowMemoryKill`, mechanism `exit_info`, level `warning` |
| Killed for excessive resource use | 30+ | type `ExcessiveResourceUsage`, mechanism `exit_info`, level `warning` |
| Main thread blocked 5 s (opt-in watchdog) | all | type `ANR`, mechanism `anr`, level `error` |

Turn it on with the config flag; it starts on the **next** launch (the
reporter is installed by a startup `ContentProvider`, before
`Application.onCreate`, and the config it reads is written by this launch):

```kotlin
import com.cohorly.android.Cohorly
import com.cohorly.android.core.CohorlyConfig

Cohorly.getInstance(
    context,
    "YOUR_PROJECT_TOKEN",
    config = CohorlyConfig(nativeCrashReporting = true, anrWatchdog = false),
)
```

That persists the crash config (`noBackupFilesDir/cohorly-crash/state.json`, key
`config`) with the release `<applicationId>@<versionName>+<versionCode>` - the
key the `@cohorly/cli` gradle plugin uploads R8 mappings under - plus the
current identity, token and host, and keeps the identity current on
`identify()` / `reset()`. `optOutTracking()` turns crash reporting off too:
nothing is recorded, and no pending report or exit record is sent, until
`optInTracking()`. Leaving the flag false never touches that config (a
React Native layer may own it). React Native apps call
`CohorlyCrashReporter.configure(context, json)` through the RN module instead.

Optional manifest meta-data, which wins over the token and host in the
persisted config (the `@cohorly/react-native` Expo plugin writes it):

```xml
<application>
    <meta-data android:name="com.cohorly.PROJECT_TOKEN" android:value="YOUR_PROJECT_TOKEN" />
    <meta-data android:name="com.cohorly.API_HOST" android:value="https://your-host" />
    <meta-data android:name="com.cohorly.ANR_WATCHDOG" android:value="true" />
</application>
```

How it behaves:

- **The handler chains.** It writes the report to
  `no_backup/cohorly-crash/pending/`, tries to send it for at most 2 s, then
  always calls the handler that was installed before it (Crashlytics, Sentry,
  the platform's). On the first activity resume it reinstalls itself on top if
  something replaced it without chaining.
- **Pending reports are sent at the next launch**, before anything else, one
  event per request, oldest first. 400/413 drop the report; 401, 429, 5xx and
  network errors keep it. At most 50 reports wait on disk.
- **Crash-time values travel with the report**: time, release, dist, identity,
  OS version and model. ANRs and native crashes are read from
  `ApplicationExitInfo` on the next launch, so their release and identity come
  from a 128-byte process state summary set at startup; without one the event
  says `android@unknown`, never the release that happens to be running now.
- **Each exit is reported once** (a timestamp watermark, plus a deterministic
  `$insert_id`). The first launch with reporting on only records the
  watermark: exits from before it are never sent.
- **Limits:** 20 threads and 100 frames per stack (the crashing end is kept);
  ANRs, native crashes and kills need API 30+; Java frames are sent as thrown
  (R8-obfuscated) and symbolicated on the server; a background low-memory kill
  is not an error and is not sent. Do not enable it alongside another native
  crash reporter's NDK/ANR handling.

## Persistence and Android backup

State (distinct id, `$device_id`, super properties, the unsent queues, the flag
cache) persists as a JSON file at
`no_backup/cohorly_<token>.json`, under the app's
[`getNoBackupFilesDir()`][no-backup].

That directory, not `shared_prefs/`, on purpose. Android's Auto Backup is on by
default and copies `shared_prefs/` to Google Drive and to the new handset on a
device-to-device transfer - which would restore this SDK's `$device_id` onto a
second physical device and have both report as one, plus re-send the old
device's unsent events. The platform excludes `no_backup/` from backup and
transfer, so **you do not have to configure `allowBackup` or backup rules for
Cohorly**; the SDK is correct under whatever backup policy your app wants.

Two consequences worth knowing:

- Upgrading from an SDK version that used SharedPreferences migrates the old
  state across on first launch, so device ids and unsent events are preserved.
- As a second line of defence, the SDK stamps the install its state belongs to.
  State stamped by a *different* install (an `adb backup`, an OEM transfer that
  ignores the platform exclusion) is treated as restored: a fresh `$device_id`
  is minted, and the queues and automatic-event bookkeeping are dropped so
  `$ae_first_open` fires on the new device. An **opt-out survives** that wipe -
  a user who opted out on the old device stays opted out on the new one.
  State with *no* stamp is adopted rather than wiped: unstamped means
  pre-stamp, which is the migration above, not another device.

[no-backup]: https://developer.android.com/reference/android/content/Context#getNoBackupFilesDir()

## Offline queue and retry behavior

Events persist in that same file so they survive process death - the iOS SDK
uses UserDefaults, React Native uses AsyncStorage; mixpanel-android uses
SQLite, but a 1000-event cap doesn't need it. The queue drops the OLDEST
events past 1000.

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
- Persistence is a JSON file under `no_backup/`, not SQLite in
  `databases/` - so SDK state is never carried to another device by
  Android Auto Backup (see "Persistence and Android backup").
- People API has no `append` / `union` / `remove` / `merge` (no server-side
  list ops), and `optOutTracking()` does not reset the distinct id.
- No `$ae_crashed` (crashes are `$exception` events, see "Crash reporting"), no in-app messaging/A-B remnants.

## Project layout / tests

- `cohorly-core/` - pure-JVM Kotlin module: queue, batching, retry, identity,
  props merging, automatic-event logic. `./gradlew :cohorly-core:test` runs the
  full JUnit suite (no emulator, no Android SDK needed).
- `cohorly/` - `com.android.library` glue: `Cohorly.getInstance`,
  `NoBackupFileStorage`, device info, lifecycle watcher, and the crash
  reporter's provider and platform adapters (`com.cohorly.android.crash`; the
  logic and its tests live in `cohorly-core`, `com.cohorly.android.core.crash`).

```bash
./gradlew test              # JVM unit tests
./gradlew :cohorly:assembleRelease  # build the AAR (requires Android SDK)
```

## Publishing

Not published to Maven Central - distributed via **JitPack**, which builds
directly from the public `cohorly-io/cohorly-android` mirror on demand, no
account/signing/namespace-verification needed. See
[docs/RELEASING.md](../../docs/RELEASING.md#sdk-releases): bump `version` in
this file's `build.gradle.kts`, then push an `android-vX.Y.Z` tag on the
monorepo - `.github/workflows/sync-sdks.yml` mirrors this directory to
`cohorly-io/cohorly-android`, tags it `vX.Y.Z`, and warms the JitPack build.
`jitpack.yml` (this directory) pins JDK 17 to match the project's toolchain.

Consumers add:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.cohorly-io.cohorly-android:cohorly:vX.Y.Z")
}
```

Once a release has actually gone through JitPack once (unverified here - no
JitPack build has ever been triggered for this repo), `packages/sdk-react-native`'s
native module can depend on it the same way instead of vendoring a copy of the
device-info code - see that package's README.
