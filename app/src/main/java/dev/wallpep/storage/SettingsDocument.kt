package dev.wallpep.storage

import dev.wallpep.model.WallpaperSettings

/**
 * The settings document's key names and its missing-key rules, with no Android types.
 *
 * The document itself is one versioned JSON blob written and read by [SettingsCodec], which
 * needs `org.json` and therefore cannot run in a local unit test. The **schema** — which key
 * holds which setting, and what a document that predates a key must decode to — is pure, and
 * lives here so it can be pinned by an ordinary JVM test.
 *
 * Keeping the names in one place also means the writer and the reader cannot drift apart:
 * both use the constants below.
 */
internal object SettingsKeys {

    /** "Show content on lockscreen" (#145). */
    const val SHOW_ON_LOCKSCREEN = "show_on_lockscreen"

    /** "Show content on homescreen" (#146). */
    const val SHOW_ON_HOMESCREEN = "show_on_homescreen"

    /** "Show battery % while charging" (#147). */
    const val BATTERY_WHILE_CHARGING = "battery_while_charging"

    /** Every key this slice added, newest first. */
    val sliceKeys = listOf(BATTERY_WHILE_CHARGING, SHOW_ON_HOMESCREEN, SHOW_ON_LOCKSCREEN)
}

/**
 * What a document that does not carry a key decodes to.
 *
 * The rule is the same everywhere: a document written before a key existed keeps the shipped
 * default, so an older document renders exactly as it did before the key was added.
 */
internal object SettingsDefaults {

    /** "Show content on lockscreen": on — the lock screen shows its content unless asked not to. */
    val showOnLockscreen: Boolean get() = WallpaperSettings().showOnLockscreen

    /** "Show content on homescreen": on. */
    val showOnHomescreen: Boolean get() = WallpaperSettings().showOnHomescreen

    /** "Show battery % while charging": off — the element is opt-in. */
    val batteryWhileCharging: Boolean get() = WallpaperSettings().batteryWhileCharging

    /**
     * The value a document without [key] decodes to.
     *
     * A document that carries the key uses the stored value; one that does not uses the
     * shipped default. This is the whole "older documents keep working" rule, in one place.
     *
     * An unknown key throws rather than falling back to `false`: a typo in a key name must
     * fail loudly instead of quietly changing a setting.
     */
    fun booleanFor(key: String, stored: Boolean?): Boolean = stored ?: when (key) {
        SettingsKeys.SHOW_ON_LOCKSCREEN -> showOnLockscreen
        SettingsKeys.SHOW_ON_HOMESCREEN -> showOnHomescreen
        SettingsKeys.BATTERY_WHILE_CHARGING -> batteryWhileCharging
        else -> throw IllegalArgumentException("Unknown settings key: $key")
    }
}
