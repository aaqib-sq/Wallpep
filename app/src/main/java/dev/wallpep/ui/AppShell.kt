package dev.wallpep.ui

import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * The two-destination shell: My Collection and Settings, with the editor presented over the top.
 * Exactly two bottom-navigation items exist; there is no shop, gifts or account destination.
 */
class AppShell(
    private val activity: ComponentActivity,
    private val home: View,
    private val settings: View,
    private val editor: View,
) {
    enum class Destination(val label: String, val glyph: String) {
        COLLECTION("My Collection", "▦"),
        SETTINGS("Settings", "⚙"),
    }

    val root = FrameLayout(activity)

    private val content = FrameLayout(activity)
    private val bottomBar = LinearLayout(activity)
    private val editorLayer = FrameLayout(activity)
    private val items = mutableMapOf<Destination, LinearLayout>()
    private var destination = Destination.COLLECTION

    init {
        content.addView(home, FrameLayout.LayoutParams(-1, -1))
        content.addView(settings, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(buildBottomBar(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        editorLayer.addView(editor, FrameLayout.LayoutParams(-1, -1))
        editorLayer.visibility = View.GONE
        root.addView(editorLayer, FrameLayout.LayoutParams(-1, -1))
        select(Destination.COLLECTION)
    }

    private fun buildBottomBar(): View {
        bottomBar.orientation = LinearLayout.HORIZONTAL
        bottomBar.setBackgroundColor(Pulse.SURFACE)
        bottomBar.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.navigationBars())
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        Destination.entries.forEach { item ->
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, activity.dp(9), 0, activity.dp(9))
                isClickable = true
                isFocusable = true
                contentDescription = item.label
                setOnClickListener { select(item) }
            }
            val glyph = activity.uiText(item.glyph, 17f, Pulse.MUTED, WallpepType.display(activity)).apply {
                gravity = Gravity.CENTER
            }
            val label = activity.uiText(item.label, 10.5f, Pulse.MUTED, WallpepType.bodyMedium(activity)).apply {
                gravity = Gravity.CENTER
            }
            cell.addView(glyph)
            cell.addView(label, activity.full(top = 2))
            items[item] = cell
            bottomBar.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bottomBar
    }

    fun select(destination: Destination) {
        this.destination = destination
        home.visibility = if (destination == Destination.COLLECTION) View.VISIBLE else View.GONE
        settings.visibility = if (destination == Destination.SETTINGS) View.VISIBLE else View.GONE
        items.forEach { (item, cell) ->
            val on = item == destination
            val glyph = cell.getChildAt(0) as TextView
            val label = cell.getChildAt(1) as TextView
            glyph.setTextColor(if (on) Pulse.ACCENT_LIGHT else Pulse.MUTED)
            label.setTextColor(if (on) Pulse.TEXT else Pulse.MUTED)
            cell.isSelected = on
        }
    }

    fun current(): Destination = destination

    fun openEditor() {
        editorLayer.visibility = View.VISIBLE
        editorLayer.bringToFront()
        bottomBar.visibility = View.GONE
    }

    fun closeEditor() {
        editorLayer.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
    }

    fun editorVisible(): Boolean = editorLayer.visibility == View.VISIBLE

    fun showCollection() {
        closeEditor()
        select(Destination.COLLECTION)
    }
}
