package dev.wallpep.ui

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.wallpep.model.AnimationStyle
import dev.wallpep.model.TimeStyle
import dev.wallpep.model.WallpaperSettings

/**
 * The cleaned Settings screen: Preferences, Wallpaper Settings, Memory and About, in that order.
 * There is no account card, no Pro or Upgrade banner, no gifts and no social links.
 */
class SettingsScreen(private val activity: ComponentActivity, private val listener: Listener) {

    interface Listener {
        fun currentSettings(): WallpaperSettings
        fun canInteract(): Boolean
        fun onSettingsChanged(next: WallpaperSettings)
        fun onClearCache()
        fun appVersion(): String
    }

    val root: View

    private lateinit var use24Hour: SwitchRow
    private lateinit var lockscreen: SwitchRow
    private lateinit var homescreen: SwitchRow
    private lateinit var battery: SwitchRow
    private lateinit var animations: SwitchRow
    private lateinit var animationStyle: LinkRow
    private lateinit var cacheValue: TextView
    private lateinit var versionValue: TextView

    init {
        root = buildScreen()
        render(WallpaperSettings())
    }

    private fun buildScreen(): View {
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

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(18), activity.dp(4), activity.dp(18), activity.dp(28))
        }

        // 1 — Preferences
        val preferences = activity.sectionCard("Preferences", fill = Pulse.SETTINGS_SURFACE)
        use24Hour = activity.switchRow(
            "Use 24-Hour Clock",
            "Off follows your device's 12/24-hour setting.",
        ) { on ->
            act { it.copy(timeStyle = if (on) TimeStyle.TWENTY_FOUR_HOUR else TimeStyle.SYSTEM) }
        }
        preferences.addView(use24Hour.root, activity.full(top = 10))
        preferences.addView(
            activity.linkRow("Hide System Clock", "How to hide the phone's own clock") { showHideClockGuide() }.root,
            activity.full(top = 10),
        )
        page.addView(preferences, activity.full(top = 14))

        // 2 — Wallpaper Settings
        val wallpaper = activity.sectionCard("Wallpaper Settings", fill = Pulse.SETTINGS_SURFACE)
        lockscreen = activity.switchRow("Show content on lockscreen", "Wallpep draws on the lock screen when the system allows it.") { on ->
            act { it.copy(showOnLockscreen = on) }
        }
        homescreen = activity.switchRow("Show content on homescreen", "Wallpep draws on the home screen.") { on ->
            act { it.copy(showOnHomescreen = on) }
        }
        battery = activity.switchRow("Show battery % while charging") { on ->
            act { it.copy(batteryWhileCharging = on) }
        }
        animations = activity.switchRow("Enable clock animations") { on ->
            act { it.copy(clockAnimations = on) }
        }
        listOf(lockscreen, homescreen, battery, animations).forEach {
            wallpaper.addView(it.root, activity.full(top = 6))
        }
        animationStyle = activity.linkRow("Animation Style", "") { showAnimationStyles() }
        wallpaper.addView(animationStyle.root, activity.full(top = 10))
        wallpaper.addView(
            activity.linkRow("Wallpaper keeps resetting?", "Why a device may restore its own wallpaper") { showResetHelp() }.root,
            activity.full(top = 10),
        )
        page.addView(wallpaper, activity.full(top = 14))

        // 3 — Memory
        val memory = activity.sectionCard("Memory", fill = Pulse.SETTINGS_SURFACE)
        cacheValue = activity.uiText("", 11f, Pulse.MUTED)
        cacheValue.text = "Cutout cache · cleared on demand"
        memory.addView(cacheValue, activity.full(top = 8))
        val clear = activity.actionButton("Clear Cache", primary = false) { listener.onClearCache() }
        memory.addView(clear, LinearLayout.LayoutParams(-1, activity.dp(44)).apply { topMargin = activity.dp(10) })
        page.addView(memory, activity.full(top = 14))

        // 4 — About
        val about = activity.sectionCard("About", fill = Pulse.SETTINGS_SURFACE)
        about.addView(
            activity.linkRow("Privacy Policy", "Everything stays on this device") { showPrivacy() }.root,
            activity.full(top = 10),
        )
        val versionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), activity.dp(13), activity.dp(14), activity.dp(13))
            background = activity.shape(Pulse.FIELD, 13f, Pulse.LINE)
        }
        versionRow.addView(activity.uiText("App Version", 13f, Pulse.TEXT, WallpepType.bodyMedium(activity)), LinearLayout.LayoutParams(0, -2, 1f))
        versionValue = activity.uiText("", 11.5f, Pulse.MUTED)
        versionRow.addView(versionValue)
        about.addView(versionRow, activity.full(top = 10))
        page.addView(about, activity.full(top = 14))

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Pulse.BACKGROUND)
            addView(page)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return column
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
        titles.addView(activity.uiText("Settings", 23f, Pulse.TEXT, WallpepType.display(activity), -0.01f))
        titles.addView(activity.uiText("Wallpep preferences", 11.5f, Pulse.MUTED), activity.full(top = 2))
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    // ---------------------------------------------------------------- state

    fun render(settings: WallpaperSettings) {
        val enabled = listener.canInteract()
        use24Hour.render(settings.timeStyle == TimeStyle.TWENTY_FOUR_HOUR, enabled)
        lockscreen.render(settings.showOnLockscreen, enabled)
        homescreen.render(settings.showOnHomescreen, enabled)
        battery.render(settings.batteryWhileCharging, enabled)
        animations.render(settings.clockAnimations, enabled)
        animationStyle.value.text = settings.animationStyle.label
        animationStyle.value.visibility = View.VISIBLE
        versionValue.text = listener.appVersion()
        animationStyle.root.alpha = if (enabled) 1f else 0.45f
    }

    private fun act(write: (WallpaperSettings) -> WallpaperSettings) {
        if (!listener.canInteract()) return
        listener.onSettingsChanged(write(listener.currentSettings()).sanitized())
    }

    // ---------------------------------------------------------------- help rows

    private fun showHideClockGuide() {
        AlertDialog.Builder(activity)
            .setTitle("Hide System Clock")
            .setMessage(
                "Android draws its own clock on the lock screen. To keep only Wallpep's clock visible:\n\n" +
                    "1. Open your device Settings → Display (or Lock screen).\n" +
                    "2. Turn off the lock screen clock, or choose the minimal style.\n" +
                    "3. Keep Wallpep as the live wallpaper and pick Home screen in the system chooser.\n\n" +
                    "Wallpep cannot change that system setting for you and asks for no extra permission.",
            )
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showAnimationStyles() {
        val styles = AnimationStyle.entries
        val labels = styles.map { it.label }.toTypedArray()
        val current = listener.currentSettings().animationStyle
        AlertDialog.Builder(activity)
            .setTitle("Animation Style")
            .setSingleChoiceItems(labels, styles.indexOf(current)) { dialog, which ->
                act { it.copy(animationStyle = styles[which], clockAnimations = true) }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResetHelp() {
        AlertDialog.Builder(activity)
            .setTitle("Wallpaper keeps resetting?")
            .setMessage(
                "Some devices restore a cached wallpaper after a restart, a theme change or a battery " +
                    "optimisation pass. If Wallpep is replaced by the stock wallpaper:\n\n" +
                    "• Re-open Wallpep and tap Set wallpaper again.\n" +
                    "• Pick Home screen only in the chooser.\n" +
                    "• Exclude Wallpep from the device's aggressive battery cleaner if one is installed.\n\n" +
                    "Wallpep never asks for a battery-optimisation exemption.",
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showPrivacy() {
        AlertDialog.Builder(activity)
            .setTitle("Privacy Policy")
            .setMessage(
                "Wallpep runs entirely on this device.\n\n" +
                    "• No account, login, analytics, ads, billing or push messaging.\n" +
                    "• No internet permission: the app cannot send or fetch anything.\n" +
                    "• The photo you pick is copied into Wallpep's private, non-backup storage and " +
                    "never leaves the device.\n" +
                    "• The depth cutout runs on-device with a bundled model.\n" +
                    "• Clearing the cache only deletes Wallpep's own cutout files.\n" +
                    "• Uninstalling Wallpep deletes every file it stored.",
            )
            .setPositiveButton("Close", null)
            .show()
    }
}
