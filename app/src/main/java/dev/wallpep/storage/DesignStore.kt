package dev.wallpep.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.wallpep.model.WallpaperSettings
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One wallpaper the user built and kept. Only the user's own designs exist here: there is no
 * catalog, no downloaded artwork and no bundled poster image.
 */
data class SavedDesign(
    val id: String,
    val name: String,
    val settings: WallpaperSettings,
    val favorited: Boolean = false,
    val inRotation: Boolean = false,
    val updatedAt: Long = 0L,
)

private val Context.designStore by preferencesDataStore(name = "wallpep_designs")

/**
 * The My Collection library. A single JSON array in Preferences DataStore keeps the whole
 * collection consistent: a save either lands completely or not at all, and a damaged entry is
 * dropped instead of breaking the screen.
 */
class DesignStore(context: Context) {
    private val store = context.applicationContext.designStore

    val designs: Flow<List<SavedDesign>> = store.data.map { prefs -> decode(prefs[designsKey]) }

    suspend fun save(all: List<SavedDesign>) {
        store.edit { prefs -> prefs[designsKey] = encode(all) }
    }

    suspend fun upsert(design: SavedDesign) {
        store.edit { prefs ->
            val current = decode(prefs[designsKey]).filterNot { it.id == design.id }
            prefs[designsKey] = encode((current + design.sanitized()).take(MAX_DESIGNS))
        }
    }

    suspend fun setFavorited(id: String, favorited: Boolean) = update(id) { it.copy(favorited = favorited) }

    suspend fun setInRotation(id: String, inRotation: Boolean) = update(id) { it.copy(inRotation = inRotation) }

    suspend fun rename(id: String, name: String) = update(id) { it.copy(name = cleanName(name)) }

    suspend fun touch(id: String, now: Long) = update(id) { it.copy(updatedAt = now) }

    suspend fun remove(id: String) {
        store.edit { prefs ->
            val current = decode(prefs[designsKey]).filterNot { it.id == id }
            prefs[designsKey] = encode(current)
        }
    }

    private suspend fun update(id: String, block: (SavedDesign) -> SavedDesign) {
        store.edit { prefs ->
            val current = decode(prefs[designsKey]).map { if (it.id == id) block(it).sanitized() else it }
            prefs[designsKey] = encode(current)
        }
    }

    private fun encode(all: List<SavedDesign>): String {
        val array = JSONArray()
        all.take(MAX_DESIGNS).forEach { design ->
            array.put(JSONObject().apply {
                put("id", design.id)
                put("name", design.name)
                put("favorited", design.favorited)
                put("in_rotation", design.inRotation)
                put("updated_at", design.updatedAt)
                put("settings", SettingsCodec.toJson(design.settings))
            })
        }
        return array.toString()
    }

    private fun decode(raw: String?): List<SavedDesign> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_DESIGNS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (!isSafeDesignId(id)) continue
                    val settings = item.optJSONObject("settings")?.let(SettingsCodec::fromJson)
                        ?: WallpaperSettings()
                    add(
                        SavedDesign(
                            id = id,
                            name = cleanName(item.optString("name")),
                            settings = settings,
                            favorited = item.optBoolean("favorited"),
                            inRotation = item.optBoolean("in_rotation"),
                            updatedAt = item.optLong("updated_at"),
                        ),
                    )
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    companion object {
        private val designsKey = stringPreferencesKey("designs")
        const val MAX_DESIGNS = 60
        private const val MAX_NAME = 40
        private val idPattern = Regex("^design-[a-f0-9-]{36}$")

        fun newId(): String = "design-" + UUID.randomUUID().toString().lowercase(Locale.ROOT)

        fun isSafeDesignId(id: String): Boolean = idPattern.matches(id)

        fun cleanName(raw: String): String {
            val trimmed = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
            return trimmed.ifEmpty { "Untitled wallpaper" }
        }

        fun sanitized(design: SavedDesign): SavedDesign = design.copy(
            name = cleanName(design.name),
            settings = design.settings.sanitized(),
        )
    }

    private fun SavedDesign.sanitized(): SavedDesign = sanitized(this)
}
