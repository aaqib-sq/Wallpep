package dev.wallpep.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import dev.wallpep.fonts.FontCatalog
import dev.wallpep.fonts.FontFamily
import dev.wallpep.fonts.FontStyles

/**
 * Searchable picker over the bundled library. Each row is drawn in its own family so the
 * choice is judged by eye; loaded faces come from the small shared LRU cache.
 */
internal object FontPickerDialog {

    private data class Row(
        val id: String,
        val title: String,
        val subtitle: String,
        val family: FontFamily?,
    )

    fun show(context: Context, title: String, allowFollowClock: Boolean, selectedId: String, onSelected: (String) -> Unit) {
        val extras = buildList {
            if (allowFollowClock) {
                add(Row(FontCatalog.FOLLOW_CLOCK, "Same as clock", "Reuse the clock typeface", null))
            }
            add(Row(FontCatalog.SYSTEM_ID, "System sans", "The device's own sans face", null))
        }
        val families = FontStyles.all.map { style ->
            val family = style.family
            Row(style.id, style.label, "${family.category.label} · ${family.license.label}", family)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(18), context.dp(2), context.dp(18), context.dp(6))
            setBackgroundColor(Pulse.SURFACE)
        }
        val countLabel = context.uiText(
            "${FontStyles.all.size} faces · ${FontCatalog.families.size} families · OFL / Apache-2.0",
            10.5f,
            Pulse.MUTED,
        )
        content.addView(countLabel)

        val search = EditText(context).apply {
            hint = "Search typefaces"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
            setTextColor(Pulse.TEXT)
            setHintTextColor(Pulse.MUTED)
            background = context.shape(Pulse.FIELD, 12f, Pulse.ACCENT_BORDER)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
        }
        content.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })

        val list = ListView(context).apply {
            dividerHeight = 0
            setBackgroundColor(Pulse.SURFACE)
        }
        val adapter = FamilyAdapter(context, selectedId)
        adapter.submit(extras + families)
        list.adapter = adapter
        // Keep the dialog inside short screens and above an open keyboard.
        val listHeight = (context.resources.displayMetrics.heightPixels * 0.42f).toInt()
            .coerceIn(context.dp(200), context.dp(360))
        content.addView(list, LinearLayout.LayoutParams(-1, listHeight).apply { topMargin = context.dp(4) })

        val empty = context.uiText("No bundled face matches that search.", 12f, Pulse.MUTED).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        content.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })

        val dialog = AlertDialog.Builder(context)
            .setTitle("$title typeface")
            .setView(content)
            .setNegativeButton("Close", null)
            .create()

        search.doAfterTextChanged { editable ->
            val query = editable?.toString().orEmpty()
            val matches = if (query.isBlank()) {
                extras + families
            } else {
                families.filter { it.title.lowercase().contains(query.trim().lowercase()) || (it.family?.id?.contains(query.trim()) == true) }
            }
            adapter.submit(matches)
            empty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.getItem(position)
            onSelected(row.id)
            dialog.dismiss()
        }
        dialog.show()
    }

    private class FamilyAdapter(private val context: Context, private val selectedId: String) : BaseAdapter() {
        private val rows = mutableListOf<Row>()

        fun submit(next: List<Row>) {
            rows.clear()
            rows.addAll(next)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = rows[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val holder = if (convertView == null) {
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(context.dp(6), context.dp(10), context.dp(6), context.dp(10))
                }
                val title = TextView(context).apply { includeFontPadding = true }
                val subtitle = TextView(context).apply { textSize = 10.5f; setTextColor(Pulse.MUTED); includeFontPadding = true }
                layout.addView(title)
                layout.addView(subtitle)
                layout.tag = title to subtitle
                layout
            } else convertView
            @Suppress("UNCHECKED_CAST")
            val pair = holder.tag as Pair<TextView, TextView>
            val title = pair.first
            val subtitle = pair.second
            val family = row.family
            title.text = row.title
            title.textSize = 19f
            title.setTextColor(if (row.id == selectedId) Pulse.ACCENT_LIGHT else Pulse.TEXT)
            title.typeface = if (family == null) {
                WallpepType.bodyMedium(context)
            } else {
                FaceCache.get(context, row.id)
            }
            subtitle.text = row.subtitle
            return holder
        }
    }
}
