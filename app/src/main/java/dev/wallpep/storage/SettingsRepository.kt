package dev.wallpep.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wallpep.fonts.FontCatalog
import dev.wallpep.model.DateStyle
import dev.wallpep.model.SETTINGS_SCHEMA_VERSION
import dev.wallpep.model.TimeStyle
import dev.wallpep.model.WallpaperSettings
import dev.wallpep.model.storedEnum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The delegate is process-wide: the activity and every wallpaper engine share one DataStore.
private val Context.settingsStore by preferencesDataStore(name = "wallpep_settings")

data class SettingsSnapshot(val settings: WallpaperSettings, val schemaSupported: Boolean)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val version = intPreferencesKey("schema_version")
        val timeStyle = stringPreferencesKey("time_style")
        val dateStyle = stringPreferencesKey("date_style")
        val font = stringPreferencesKey("font")
        val dateFont = stringPreferencesKey("date_font")
        val textColor = intPreferencesKey("text_color")
        val dateColor = intPreferencesKey("date_color")
        val clockSize = intPreferencesKey("clock_size_sp")
        val clockLetterSpacing = intPreferencesKey("clock_letter_spacing")
        val clockOpacity = intPreferencesKey("clock_opacity")
        val showClock = booleanPreferencesKey("show_clock")
        val x = intPreferencesKey("position_x")
        val y = intPreferencesKey("position_y")
        val dateSize = intPreferencesKey("date_size_sp")
        val dateLetterSpacing = intPreferencesKey("date_letter_spacing")
        val dateOpacity = intPreferencesKey("date_opacity")
        val dateUppercase = booleanPreferencesKey("date_uppercase")
        val dateX = intPreferencesKey("date_position_x")
        val dateY = intPreferencesKey("date_position_y")
        val background = stringPreferencesKey("background_file")
        val hourOverForeground = booleanPreferencesKey("hour_over_foreground")
        val minuteOverForeground = booleanPreferencesKey("minute_over_foreground")
        val dateOverForeground = booleanPreferencesKey("date_over_foreground")
    }

    val snapshots: Flow<SettingsSnapshot> = store.data.map { prefs ->
        val defaults = WallpaperSettings()
        val version = prefs[Keys.version] ?: 0 // no key = first install, or pre-schema defaults
        if (version > SETTINGS_SCHEMA_VERSION) {
            // Never overwrite a future schema, including if an older APK is installed later.
            SettingsSnapshot(defaults, schemaSupported = false)
        } else {
            SettingsSnapshot(
                WallpaperSettings(
                    timeStyle = storedEnum(prefs[Keys.timeStyle], defaults.timeStyle),
                    dateStyle = storedEnum(prefs[Keys.dateStyle], defaults.dateStyle),
                    // Sanitizing migrates the Phase 1/2 enum names (OUTFIT, DM_SANS, SYSTEM).
                    font = FontCatalog.sanitizeId(prefs[Keys.font]),
                    dateFont = FontCatalog.sanitizeDateId(prefs[Keys.dateFont]),
                    textColor = prefs[Keys.textColor] ?: defaults.textColor,
                    // A missing key means the date follows the clock colour.
                    dateColor = prefs[Keys.dateColor],
                    clockSizeSp = prefs[Keys.clockSize] ?: defaults.clockSizeSp,
                    clockLetterSpacing = prefs[Keys.clockLetterSpacing] ?: defaults.clockLetterSpacing,
                    clockOpacity = prefs[Keys.clockOpacity] ?: defaults.clockOpacity,
                    showClock = prefs[Keys.showClock] ?: defaults.showClock,
                    positionX = prefs[Keys.x] ?: defaults.positionX,
                    positionY = prefs[Keys.y] ?: defaults.positionY,
                    dateSizeSp = prefs[Keys.dateSize] ?: defaults.dateSizeSp,
                    dateLetterSpacing = prefs[Keys.dateLetterSpacing] ?: defaults.dateLetterSpacing,
                    dateOpacity = prefs[Keys.dateOpacity] ?: defaults.dateOpacity,
                    dateUppercase = prefs[Keys.dateUppercase] ?: defaults.dateUppercase,
                    datePositionX = prefs[Keys.dateX] ?: defaults.datePositionX,
                    datePositionY = prefs[Keys.dateY] ?: defaults.datePositionY,
                    backgroundName = prefs[Keys.background],
                    hourOverForeground = prefs[Keys.hourOverForeground] ?: defaults.hourOverForeground,
                    minuteOverForeground = prefs[Keys.minuteOverForeground] ?: defaults.minuteOverForeground,
                    dateOverForeground = prefs[Keys.dateOverForeground] ?: defaults.dateOverForeground,
                ).sanitized(),
                schemaSupported = true,
            )
        }
    }

    suspend fun save(settings: WallpaperSettings) {
        val clean = settings.sanitized()
        store.edit { prefs ->
            check((prefs[Keys.version] ?: 0) <= SETTINGS_SCHEMA_VERSION) {
                "Settings were written by a newer version of Wallpep"
            }
            prefs[Keys.version] = SETTINGS_SCHEMA_VERSION
            prefs[Keys.timeStyle] = clean.timeStyle.name
            prefs[Keys.dateStyle] = clean.dateStyle.name
            prefs[Keys.font] = clean.font
            prefs[Keys.dateFont] = clean.dateFont
            prefs[Keys.textColor] = clean.textColor
            if (clean.dateColor == null) prefs.remove(Keys.dateColor) else prefs[Keys.dateColor] = clean.dateColor
            prefs[Keys.clockSize] = clean.clockSizeSp
            prefs[Keys.clockLetterSpacing] = clean.clockLetterSpacing
            prefs[Keys.clockOpacity] = clean.clockOpacity
            prefs[Keys.showClock] = clean.showClock
            prefs[Keys.x] = clean.positionX
            prefs[Keys.y] = clean.positionY
            prefs[Keys.dateSize] = clean.dateSizeSp
            prefs[Keys.dateLetterSpacing] = clean.dateLetterSpacing
            prefs[Keys.dateOpacity] = clean.dateOpacity
            prefs[Keys.dateUppercase] = clean.dateUppercase
            prefs[Keys.dateX] = clean.datePositionX
            prefs[Keys.dateY] = clean.datePositionY
            prefs[Keys.hourOverForeground] = clean.hourOverForeground
            prefs[Keys.minuteOverForeground] = clean.minuteOverForeground
            prefs[Keys.dateOverForeground] = clean.dateOverForeground
            if (clean.backgroundName == null) prefs.remove(Keys.background)
            else prefs[Keys.background] = clean.backgroundName
        }
    }
}
