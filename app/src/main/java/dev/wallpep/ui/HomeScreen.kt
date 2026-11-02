package dev.wallpep.ui

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.wallpep.storage.SavedDesign
import java.util.Locale

/**
 * "My Collection": the user's own saved wallpapers only. Each card is a real render of that
 * design's photo with its clock and date overlay plus a favourite star. There is no catalog,
 * no celebrity or poster artwork and no shop row anywhere on this screen.
 */
class HomeScreen(private val activity: ComponentActivity, private val listener: Listener) {

    interface Listener {
        fun onCreateDesign()
        fun onOpenDesign(id: String)
        fun onToggleFavorite(id: String)
        fun onDesignOptions(design: SavedDesign)
    }

    private enum class Category(val label: String) {
        ALL("All Wallpapers"),
        FAVORITES("Favorited Designs"),
        TRENDING("Hot Trending"),
        ROTATION("Auto Rotate Cycle"),
    }

    val root: FrameLayout

    private lateinit var search: android.widget.EditText
    private lateinit var categoryRow: ChipRow
    private lateinit var grid: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyBody: TextView
    private lateinit var emptyAction: View
    private lateinit var emptyState: View
    private lateinit var countLabel: TextView

    private var designs: List<SavedDesign> = emptyList()
    private val thumbnails = mutableMapOf<String, Bitmap>()
    private var category = Category.ALL
    private var query = ""

    init {
        root = buildScreen()
        render(emptyList())
    }

    // ---------------------------------------------------------------- layout

    private fun buildScreen(): FrameLayout {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pulse.BACKGROUND)
        }
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        column.addView(buildHeader())
        column.addView(buildSearch())

        val strip = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(activity.dp(18), activity.dp(2), activity.dp(18), activity.dp(10))
            clipToPadding = false
        }
        categoryRow = activity.chipRow(Category.entries.map { it.label }, columns = 2) { index ->
            category = Category.entries[index]
            applyFilter()
        }
        strip.addView(categoryRow.root)
        column.addView(strip)

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), 0, activity.dp(18), activity.dp(24))
        }
        grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        page.addView(grid)
        page.addView(buildEmptyState())
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Pulse.BACKGROUND)
            addView(page)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val container = FrameLayout(activity)
        container.addView(column, FrameLayout.LayoutParams(-1, -1))
        return container
    }

    private fun buildHeader(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(18), activity.dp(14), activity.dp(18), activity.dp(6))
        }
        row.addView(activity.logoMark(38))
        val titles = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(12), 0, 0, 0)
        }
        titles.addView(activity.uiText("My Collection", 23f, Pulse.TEXT, WallpepType.display(activity), -0.01f))
        countLabel = activity.uiText("", 11.5f, Pulse.MUTED)
        titles.addView(countLabel, activity.full(top = 2))
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        val add = activity.uiText("+", 22f, Pulse.ON_ACCENT, WallpepType.display(activity)).apply {
            gravity = Gravity.CENTER
            background = activity.shape(Pulse.ACCENT, 13f)
            isClickable = true
            isFocusable = true
            contentDescription = "Create a new wallpaper"
            setOnClickListener { listener.onCreateDesign() }
        }
        row.addView(add, LinearLayout.LayoutParams(activity.dp(42), activity.dp(42)))
        return row
    }

    private fun buildSearch(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(18), activity.dp(8), activity.dp(18), activity.dp(8))
        }
        search = activity.textField("Search your wallpapers", onChange = { value ->
            query = value
            applyFilter()
        }).apply {
            contentDescription = "Search your own saved wallpapers"
        }
        row.addView(search, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun buildEmptyState(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(16), activity.dp(48), activity.dp(16), activity.dp(24))
        }
        val mark = activity.uiText("◍", 34f, Pulse.ACCENT_LIGHT, WallpepType.display(activity)).apply {
            gravity = Gravity.CENTER
            background = activity.shape(Pulse.ACCENT_WASH, 100f, Pulse.ACCENT_BORDER)
        }
        column.addView(mark, LinearLayout.LayoutParams(activity.dp(72), activity.dp(72)))
        emptyTitle = activity.uiText("No wallpapers yet", 17f, Pulse.TEXT, WallpepType.display(activity)).apply {
            gravity = Gravity.CENTER
        }
        column.addView(emptyTitle, activity.full(top = 16))
        emptyBody = activity.uiText(
            "No wallpapers yet — create your first. Pick a photo, style the clock and date, then apply it.",
            12.5f,
            Pulse.MUTED,
        ).apply {
            gravity = Gravity.CENTER
            setLineSpacing(activity.dp(3).toFloat(), 1f)
        }
        column.addView(emptyBody, activity.full(top = 8))
        val action = activity.actionButton("Create your first wallpaper", primary = true) {
            listener.onCreateDesign()
        }
        emptyAction = action
        column.addView(action, LinearLayout.LayoutParams(-1, activity.dp(48)).apply {
            topMargin = activity.dp(18)
        })
        emptyState = column
        return column
    }

    // ---------------------------------------------------------------- state

    fun render(designs: List<SavedDesign>) {
        this.designs = designs
        applyFilter()
    }

    fun setThumbnail(id: String, bitmap: Bitmap?) {
        if (bitmap == null) thumbnails.remove(id) else thumbnails[id] = bitmap
        val holder = grid.findViewWithTag<ImageView>(id)
        if (holder != null && bitmap != null) holder.setImageBitmap(bitmap)
    }

    /** Ids whose render is on screen but still waiting for a thumbnail. */
    fun pendingThumbnails(): List<String> = visible().map { it.id }.filterNot { thumbnails.containsKey(it) }

    private fun visible(): List<SavedDesign> {
        val needle = query.trim().lowercase(Locale.ROOT)
        val filtered = designs.filter { design ->
            when (category) {
                Category.ALL -> true
                Category.FAVORITES -> design.favorited
                Category.TRENDING -> true
                Category.ROTATION -> design.inRotation
            }
        }.filter { design -> needle.isEmpty() || design.name.lowercase(Locale.ROOT).contains(needle) }

        return when (category) {
            // "Hot Trending" is the user's own collection ordered by most recently edited.
            Category.TRENDING -> filtered.sortedByDescending { it.updatedAt }
            Category.ALL -> filtered.sortedByDescending { it.updatedAt }
            else -> filtered
        }
    }

    private fun applyFilter() {
        categoryRow.render(category.ordinal)
        countLabel.text = when (designs.size) {
            0 -> "Your own saved wallpapers"
            1 -> "1 saved wallpaper"
            else -> "${designs.size} saved wallpapers"
        }
        grid.removeAllViews()
        val visible = visible()
        val showEmpty = visible.isEmpty()
        emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        grid.visibility = if (showEmpty) View.GONE else View.VISIBLE
        emptyAction.visibility = if (designs.isEmpty()) View.VISIBLE else View.GONE
        when {
            designs.isEmpty() -> {
                emptyTitle.text = "No wallpapers yet"
                emptyBody.text = "No wallpapers yet — create your first. Pick a photo, style the clock and date, then apply it."
            }
            category == Category.FAVORITES -> {
                emptyTitle.text = "Nothing favourited"
                emptyBody.text = "Tap the star on a wallpaper to keep it here."
            }
            category == Category.ROTATION -> {
                emptyTitle.text = "Rotate cycle is empty"
                emptyBody.text = "Long-press a wallpaper and add it to the auto rotate cycle."
            }
            else -> {
                emptyTitle.text = "Nothing matches"
                emptyBody.text = "No saved wallpaper matches that search."
            }
        }
        if (showEmpty) return

        visible.chunked(2).forEach { row ->
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { index, design ->
                line.addView(card(design), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index == 1) marginStart = activity.dp(12)
                })
            }
            if (row.size == 1) {
                // Keeps a lone card at half width instead of stretching it across the row.
                line.addView(View(activity), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = activity.dp(12) })
            }
            grid.addView(line, activity.full(top = 12))
        }
    }

    private fun card(design: SavedDesign): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(activity).apply {
            background = activity.shape(Pulse.SURFACE, 16f, Pulse.LINE)
            clipToOutline = true
            isClickable = true
            isFocusable = true
            contentDescription = design.name
            setOnClickListener { listener.onOpenDesign(design.id) }
            setOnLongClickListener { listener.onDesignOptions(design); true }
        }
        val aspect = activity.dp(CARD_HEIGHT_DP)
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Pulse.SURFACE_RAISED)
            tag = design.id
        }
        thumbnails[design.id]?.let { image.setImageBitmap(it) }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))

        val star = activity.uiText(if (design.favorited) "♥" else "♡", 16f, if (design.favorited) Pulse.ACCENT else Pulse.TEXT).apply {
            gravity = Gravity.CENTER
            background = activity.shape(0xB30D0E13.toInt(), 100f)
            isClickable = true
            isFocusable = true
            contentDescription = if (design.favorited) "Remove ${design.name} from favourites" else "Favourite ${design.name}"
            setOnClickListener { listener.onToggleFavorite(design.id) }
        }
        frame.addView(
            star,
            FrameLayout.LayoutParams(activity.dp(32), activity.dp(32), Gravity.TOP or Gravity.END).apply {
                topMargin = activity.dp(8)
                marginEnd = activity.dp(8)
            },
        )
        column.addView(frame, LinearLayout.LayoutParams(-1, aspect))

        val name = activity.uiText(design.name, 12.5f, Pulse.TEXT, WallpepType.bodyMedium(activity)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(name, activity.full(top = 7))
        val meta = activity.uiText(
            if (design.inRotation) "In rotate cycle" else "Saved wallpaper",
            10f,
            Pulse.MUTED,
        )
        column.addView(meta, activity.full(top = 1))
        return column
    }

    private companion object {
        const val CARD_HEIGHT_DP = 232
    }
}
