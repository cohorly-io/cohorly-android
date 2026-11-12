package com.cohorly.android.core

/** What [guardRestoredInstall] concluded about the state it found. */
enum class InstallState {
    /** No prior state - a genuine first run. */
    FIRST_RUN,

    /** The stamp matches: same install, state is ours to keep. */
    SAME_INSTALL,

    /**
     * State that predates the stamp (migrated out of SharedPreferences, or
     * written before an interrupted stamp). Adopted as this install's own and
     * stamped - see [guardRestoredInstall] for why it is not wiped.
     */
    ADOPTED,

    /** State stamped by a DIFFERENT install (a backup restore); cleared. */
    RESTORED,
}

/**
 * State that belongs to one install and must not follow a backup to another:
 * identity, the unsent queues, the per-identity flag cache, and the
 * automatic-event bookkeeping (a kept `did_first_open` would stop
 * `$ae_first_open` ever firing on the new device).
 *
 * `opt_out` is deliberately absent - a user who opted out on the old device
 * must stay opted out on the new one, so it survives the wipe.
 */
private val RESTORE_CLEARED_KEYS = listOf(
    CohorlyCore.Keys.DISTINCT_ID,
    CohorlyCore.Keys.DEVICE_ID,
    CohorlyCore.Keys.ANONYMOUS,
    CohorlyCore.Keys.SUPER_PROPS,
    CohorlyCore.Keys.EVENT_QUEUE,
    CohorlyCore.Keys.ENGAGE_QUEUE,
    CohorlyCore.Keys.FLAGS,
    AutomaticEvents.Keys.DID_FIRST_OPEN,
    AutomaticEvents.Keys.LAST_VERSION,
)

/**
 * Defence in depth behind `NoBackupFileStorage`: clears state a DIFFERENT
 * install of the app stamped.
 *
 * Storing under `no_backup/` already keeps SDK state out of Auto Backup, so in
 * the normal case this only ever stamps. It earns its keep for state that
 * reached the device another way, e.g. an `adb backup` or an OEM transfer that
 * ignores the platform exclusion.
 *
 * Unstamped state is ADOPTED, never wiped. That asymmetry is the important
 * part: state with no stamp is state written before stamping existed (the
 * SharedPreferences migration) or state whose stamp write did not land, and
 * both of those are overwhelmingly the same install. Wiping on a missing stamp
 * would mean any crash in the window around the migration costs a real user
 * their `$device_id` and unsent events - a far likelier and worse outcome than
 * carrying a pre-stamp restore forward one time. Only a stamp that is present
 * and different is positive evidence of another install.
 *
 * [installStamp] must change when, and only when, the app is installed anew:
 * the Android module passes `PackageInfo.firstInstallTime`, which survives app
 * updates and changes on a restore-into-a-fresh-install. Call this BEFORE
 * constructing [CohorlyCore], which reads identity in its constructor.
 */
fun guardRestoredInstall(storage: CohorlyStorage, installStamp: String): InstallState {
    val stored = storage.get(CohorlyCore.Keys.INSTALL_STAMP)
    if (stored == installStamp) return InstallState.SAME_INSTALL

    val hasState = storage.get(CohorlyCore.Keys.DISTINCT_ID) != null
    if (stored != null) {
        for (key in RESTORE_CLEARED_KEYS) storage.set(key, null)
        storage.set(CohorlyCore.Keys.INSTALL_STAMP, installStamp)
        return InstallState.RESTORED
    }

    storage.set(CohorlyCore.Keys.INSTALL_STAMP, installStamp)
    return if (hasState) InstallState.ADOPTED else InstallState.FIRST_RUN
}

/**
 * Key under which [guardRestoredInstall] records the owning install, exposed
 * so the platform module can write it in the SAME atomic store write as the
 * state it migrates - two writes would leave a window where a process death
 * strands migrated state without its stamp.
 */
const val INSTALL_STAMP_KEY: String = CohorlyCore.Keys.INSTALL_STAMP
