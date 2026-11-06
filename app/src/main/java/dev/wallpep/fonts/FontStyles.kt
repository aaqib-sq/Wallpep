package dev.wallpep.fonts

import java.util.Locale

/** A concrete bundled file. Representative files keep their original family id for migration. */
data class FontStyle(val id: String, val family: FontFamily, val assetPath: String, val label: String)

object FontStyles {
    val all: List<FontStyle> by lazy {
        FontCatalog.families.flatMap { family ->
            bundledFontFiles[family.id].orEmpty().map { file ->
                val path = family.assetPath.substringBeforeLast('/') + "/" + file
                val id = if (path == family.assetPath) family.id else family.id + "@" + file.lowercase(Locale.ROOT)
                val style = file.removeSuffix(".ttf").substringAfter('-', "Regular")
                FontStyle(id, family, path, "${family.label} · $style")
            }
        }
    }
    private val byId: Map<String, FontStyle> by lazy { all.associateBy { it.id } }
    fun find(id: String?): FontStyle? = id?.lowercase(Locale.ROOT)?.let(byId::get)
}
