# Cohorly Android SDK

<!-- cohorly-mirror-notice:start -->
> **This repository is a read-only mirror** of a private monorepo. Its
> contents are generated on every release, so changes made here would be
> overwritten.
>
> - Pull requests are not accepted for now.
> - Bugs and questions: open an issue in this repository.
> - Security vulnerabilities: follow [SECURITY.md](./SECURITY.md). Do not open
>   a public issue.
<!-- cohorly-mirror-notice:end -->


Official Android client SDK for [Cohorly](../../README.md), the hosted
Mixpanel-style product analytics platform. Kotlin, minSdk 21, zero third-party
runtime dependencies (JSON via the platform's `org.json`, networking via
`HttpURLConnection`). The optional session replay recorder
(`cohorly-android-replay`) adds Square's curtains.

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
    implementation("com.github.cohorly-io.cohorly-android:cohorly-android:v0.5.0")
}
```

**Maven local** (development):

```bash
cd sdks/android
./gradlew publishToMavenLocal
```

```kotlin
dependencies {
    implementation("com.cohorly:cohorly-android:0.5.0") // mavenLocal()
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

## Screen views

`CohorlyConfig(trackScreenViews = true)` (default **false**, independent of
session replay: turning replay on never turns it on) tracks a `$screen` event
each time an Activity becomes visible. Its only own property is
`$screen_name`, the Activity's simple class name (`CheckoutActivity`), the same
name the replay writes in the Meta `href`. The event goes through `track`, so it carries the default and
super properties and `$session_id` in a recorded session.

- No event on a return from background, a dismissed dialog or back from
  another app (the Activity that produced the last `$screen` does not
  produce another one), nor on a rotation or other configuration change.
- Going back to a previous Activity (A -> B -> back -> A) tracks A again, and
  so does a new instance of the same class.
- Fragments and Navigation Compose destinations are not tracked: track
  `$screen` by hand for those (`track("\$screen", mapOf("\$screen_name" to "Cart"))`).
- Every Activity of the process counts, including transparent ones opened by
  libraries (billing, sign-in): each one, and the return to the caller after
  it, is a `$screen`.
- After process death (or "Don't keep activities"), returning to the app
  recreates the Activity and tracks a `$screen`.
- On Android 7 to 9 in multi-window, only the focused Activity is resumed, so
  each focus change between the two visible Activities tracks a `$screen`
  (Android 10 and later keep both resumed and do not).
- Every `$screen` counts against the monthly event quota like any other
  event.

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
| Native crash (NDK signal) | 30+ | mechanism `exit_info`, level `fatal`; API 31+: type = signal (`SIGSEGV`), tombstone frames; API 30: no tombstone, so type `NativeCrash`, value = the exit description (`crash`), no frames |
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

## Session replay

Session replay records the screen as **wireframes** (the position, size,
colors and text of each view) and plays it back in the Cohorly dashboard. By
default no screenshot is taken: the only pixels that leave the device are the
pictures of image views and image backgrounds, as small JPEGs, and only with
`maskAllImages = false`. The recorder is a separate, optional artifact, so an
app without it carries no replay code:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.cohorly-io.cohorly-android:cohorly-android:v0.5.0")
    implementation("com.github.cohorly-io.cohorly-android:cohorly-android-replay:v0.5.0")
    // or, from mavenLocal(): com.cohorly:cohorly-android-replay:0.5.0
}
```

```kotlin
import com.cohorly.android.core.replay.SessionReplayConfig

Cohorly.getInstance(
    context,
    "YOUR_PROJECT_TOKEN",
    config = CohorlyConfig(
        sessionReplay = SessionReplayConfig(sampleRate = 0.1, onErrorSampleRate = 1.0, maskAllImages = true),
    ),
)
```

`SessionReplayConfig(sampleRate = 0.0, onErrorSampleRate = 0.0, maskAllImages = true)`
has the web SDK's meaning: `sampleRate` is the share of sessions recorded in
full, `onErrorSampleRate` the share of the others whose recent recording is
sent when an error happens (see "Error mode" below). Rates outside 0..1 are
clamped. Leaving
`sessionReplay` null (the default) turns all of it off. With `sessionReplay`
set and no `cohorly-android-replay` dependency, the SDK logs a warning and only
stamps `$session_id`.

The recorder's overhead (CPU, memory, frame time) has not been measured on a
physical device yet, only exercised on emulators: measure it on your own
entry-level devices before turning `sampleRate` up in production.

- **Requirements.** Android 8.0 (API 26) or newer; below that nothing is
  recorded and `$session_id` still works.
- **Which windows.** Every window of the app's process that
  [curtains](https://github.com/square/curtains) reports: Activities, Dialogs
  (bottom sheets included) and PopupWindows (menus, spinners' lists), stacked
  by window type in one snapshot. The screen name is the Activity of the
  topmost Activity or Dialog window. Windows of other apps and of the system
  (the keyboard, notifications, permission dialogs) are never recorded, and
  nothing is captured while the app is in background.
- **What is recorded.** Those windows at most once per second while the
  screen changes and never while it is still, plus touch start/end with the
  touched view. A full snapshot at the start of each session, on the return
  from background, when the windows change (another Activity, a dialog opens)
  and when views move to another parent or change order; small diffs
  otherwise.
- **Masking, always on.** Every `EditText`, every editable `TextView` (one
  with a `KeyListener`) and every password field (a password input type or
  `PasswordTransformationMethod`) has its text replaced by `*` of the same
  length; the hint may show. There is no option to turn this off. Images are
  hidden by default (`maskAllImages = true`: the player draws a placeholder);
  with `maskAllImages = false` they are sent as small JPEGs. A `FLAG_SECURE`
  window is recorded as one empty block, and a touch on it is not recorded.
- **Hide a view.** Give it the tag `cohorly-no-capture`
  (`CohorlyReplay.NO_CAPTURE_TAG`, or any String tag that contains it, e.g.
  React Native's `testID`): the view and everything inside it become one
  empty block. `contentDescription` is never read.

  ```kotlin
  cardView.tag = CohorlyReplay.NO_CAPTURE_TAG
  ```
  ```xml
  <LinearLayout android:tag="cohorly-no-capture" ... />
  ```
- **Limitations.** `WebView` is drawn as an empty web block (its page is not
  recorded), `SurfaceView` and `TextureView` (video, maps, camera, games) as
  empty placeholders. Custom drawing (`onDraw`) is not reproduced: a custom
  view shows as a box with its background color.
- **Screenshots and Jetpack Compose.** `SessionReplayConfig(captureMode =
  ReplayCaptureMode.SCREENSHOT, screenshotQuality = 30)` records a WebP image
  of the screen instead of wireframes (default `ReplayCaptureMode.WIREFRAME`;
  quality 0 to 100). The same fields, passwords, images (per `maskAllImages`)
  and no-capture views are covered by an opaque block in the pixels before the
  image leaves the device, `FLAG_SECURE` windows whole; a frame whose masks
  cannot be trusted is dropped. Compose screens are recorded in both modes
  from their semantics (texts, fields, images); keep a composable out with
  `Modifier.cohorlyNoCapture()` (`com.cohorly.android.replay.compose`), which
  covers its whole subtree. Compose is optional: an app without it loads none
  of this.
- **Chunks.** The recording is queued every 10 seconds (sooner at 512 KiB, and
  when the app goes to background) into the upload queue below. The
  `sdks/android/sample` app records a sample screen against a local server
  (`./gradlew :sample:installDebug -PcohorlySampleToken=<token>`, plus
  `-PcohorlySampleCaptureMode=SCREENSHOT` for screenshots; it has a Compose
  screen too).
- **Error mode (`onErrorSampleRate`).** A session drawn for error recording
  keeps its recent recording in memory only; nothing leaves the device until
  an error does. It needs `nativeCrashReporting = true`, and since the crash
  reporter starts on the launch **after** the one that turned it on, error
  mode records from that launch on (without a running crash reporter, a
  session drawn for recording on error records nothing). What sends the buffer:
  - a main thread blocked 5 s (the opt-in ANR watchdog): the buffer is queued
    at once, at most one send every 5 s (a later error waits for the rest of
    the interval, then goes with the same session's buffer);
  - an uncaught exception: the buffer is written to the upload queue on the
    crashing thread, before the crash report's own 2 s send, and goes out on
    the next launch, under the same `$session_id` as the `$exception`.

  Each send starts at a full snapshot, covers at least the last 30 s when
  the recording is that old and never holds activity older than 60 s before
  the error. A full snapshot is taken every 30 s while the screen changes, so
  such a start exists. The buffer holds at most 10 MB of events (the oldest
  snapshots go first, whole). A change between full and error recording (the
  replay quota pause), a new session and an opt-out drop it. ANRs and native
  crashes read from `ApplicationExitInfo` on the next launch send no buffer.

- **`$session_id`.** With `sessionReplay` set, even with both rates at 0, every
  tracked event carries `$session_id` (a random UUID), and so does every
  `$exception` the crash reporter records in the running process (an uncaught
  exception, the ANR watchdog), read from memory when the crash is captured,
  so it is the session the crash happened in. A `$session_id` you pass
  yourself wins. Read
  the current one with `cohorly.replaySessionId`. ANRs, native crashes and
  kills read from `ApplicationExitInfo` on the next launch do **not** carry
  it: their values come from the 128-byte process state summary, which is
  already full.
- **Settings from the dashboard.** With
  `SessionReplayConfig(sampleRate = 0.0, remoteConfig = true)`, the rates and
  the minimum duration saved in Settings > Session replay apply without a new
  release. Each field the project saved wins over the same field in your
  code; a field it never saved keeps your code's value, and with no network
  and no stored copy your code's values apply. Masking and capture mode
  always come from your code. The SDK keeps the settings for up to 1 hour
  (`remote_config.json` next to `state.json`) and a change applies to new
  sessions only. A new session gets its `$session_id` at once and records
  nothing until the settings arrive, at most 3 seconds later. With a minimum
  duration, a fully recorded session sends nothing until it is that long; a
  shorter one is never sent and never counts toward the quota (error mode is
  always sent). Triggers are web only. Without `remoteConfig` (the default)
  the SDK never asks.
- **Session rotation.** A new session (new id, a new sampling draw) starts on
  the first foreground after 30 minutes in background, on the first activity
  (a tracked event or a recorded touch) after 30 minutes in foreground without
  any, on the first event once the session is 24 hours old, at once on `reset()`, and at launch when the app
  was last active more than 30 minutes ago. Activity is written at most once a
  minute, so at launch the threshold is 31 minutes since the last written
  activity: a relaunch may extend a session by up to 1 minute, never cut one
  short. The sampling draw happens once per session and is kept with it, so a
  relaunch in the same session never draws again.
- **Upload queue.** Recorded chunks are gzip files in
  `no_backup/cohorly_replay_<token>/` (never backed up, see below), next to the
  replay state (`state.json`: session, `seq` counters, quota pause), sent to
  `POST <apiHost>/replay` oldest first from their own thread
  (`cohorly-replay-upload`). Writes and session listeners run on
  `cohorly-replay`, which never waits on the network. A chunk is queued with
  the session id, distinct id and `seq` of the same moment, and one recorded
  for a session that has since rotated is refused. They never enter
  the event queue or its 1000-event cap. The directory is capped at 10 MB; past
  that the oldest chunks are deleted. A chunk queued before a rotation is still
  sent under the session it was recorded in.
- **Retry.** 429, 408, 5xx and network errors keep the chunk and back off like
  events do (base 2 s, doubling, capped at `maxRetryDelayMs`, +/-20% jitter,
  `Retry-After` honored); a 401 waits the maximum delay; a 413, or a chunk over
  1 MB, is split in two (a single event too large is dropped); any other 4xx
  drops the chunk; a 404 stops replay until the next launch. When the project's
  replay quota is used up, full-session recording pauses until the server's
  `Retry-After` (one hour without it), across launches: that session's queued
  full-session chunks are dropped and, like the web SDK, a session sampled for
  full recording buffers like an error-mode one until the pause ends. Replay
  failures never delay event uploads, and the other way round.
- **Opt-out.** `optOutTracking()` stops the recorder, drops what it held in
  memory, deletes every pending chunk and ends the
  session (`replaySessionId` is null, no event carries `$session_id`) until
  `optInTracking()`, which starts a new one (and recording again). A chunk that could not be deleted
  is never sent and is deleted again at the next launch.

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
  Session replay follows the same split: the session, chunk queue and
  uploader live in `com.cohorly.android.core.replay`.
- `cohorly-replay/` - the optional `com.android.library` recorder
  (`cohorly-android-replay`): curtains for windows and touches, wireframes,
  chunks. Parts are adapted from posthog-android (MIT): the notice and license
  are in [`NOTICE`](NOTICE). Robolectric tests: `./gradlew :cohorly-replay:testDebugUnitTest`.
- `sample/` - unpublished app that records a sample screen
  (`-PcohorlySampleToken=<token>`, `-PcohorlySampleApiHost=<url>`, default
  `http://10.0.2.2:4000`).

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
