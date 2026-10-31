package dev.wallpep.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wallpep.model.DateStyle
import dev.wallpep.model.FontChoice
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
        val textColor = intPreferencesKey("text_color")
        val clockSize = intPreferencesKey("clock_size_sp")
        val x = intPreferencesKey("position_x")
        val y = intPreferencesKey("position_y")
        val background = stringPreferencesKey("background_file")
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
                    font = storedEnum(prefs[Keys.font], defaults.font),
                    textColor = prefs[Keys.textColor] ?: defaults.textColor,
                    clockSizeSp = prefs[Keys.clockSize] ?: defaults.clockSizeSp,
                    positionX = prefs[Keys.x] ?: defaults.positionX,
                    positionY = prefs[Keys.y] ?: defaults.positionY,
                    backgroundName = prefs[Keys.background],
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
            prefs[Keys.font] = clean.font.name
            prefs[Keys.textColor] = clean.textColor
            prefs[Keys.clockSize] = clean.clockSizeSp
            prefs[Keys.x] = clean.positionX
            prefs[Keys.y] = clean.positionY
            if (clean.backgroundName == null) prefs.remove(Keys.background)
            else prefs[Keys.background] = clean.backgroundName
        }
    }
}
