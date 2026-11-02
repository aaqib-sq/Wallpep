package dev.wallpep.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wallpep.model.SETTINGS_SCHEMA_VERSION
import dev.wallpep.model.WallpaperSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The delegate is process-wide: the activity and every wallpaper engine share one DataStore.
private val Context.settingsStore by preferencesDataStore(name = "wallpep_settings")

data class SettingsSnapshot(val settings: WallpaperSettings, val schemaSupported: Boolean)

/**
 * Persists the whole active configuration as one versioned JSON document. A missing document is
 * a first install; a document from a newer schema is reported rather than overwritten.
 */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val version = intPreferencesKey("schema_version")
        val document = stringPreferencesKey("settings_json")
    }

    val snapshots: Flow<SettingsSnapshot> = store.data.map { prefs ->
        val version = prefs[Keys.version] ?: 0 // no key = first install, or pre-schema defaults
        if (version > SETTINGS_SCHEMA_VERSION) {
            // Never overwrite a future schema, including if an older APK is installed later.
            SettingsSnapshot(WallpaperSettings(), schemaSupported = false)
        } else {
            val stored = SettingsCodec.decode(prefs[Keys.document])
            SettingsSnapshot((stored ?: WallpaperSettings()).sanitized(), schemaSupported = true)
        }
    }

    suspend fun save(settings: WallpaperSettings) {
        val clean = settings.sanitized()
        val document = SettingsCodec.encode(clean)
        store.edit { prefs ->
            check((prefs[Keys.version] ?: 0) <= SETTINGS_SCHEMA_VERSION) {
                "Settings were written by a newer version of Wallpep"
            }
            prefs[Keys.version] = SETTINGS_SCHEMA_VERSION
            prefs[Keys.document] = document
        }
    }
}
