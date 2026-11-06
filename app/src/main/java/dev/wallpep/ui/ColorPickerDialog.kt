package dev.wallpep.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import dev.wallpep.model.ColorHex

/** Full ARGB editor, shared by fill, gradient, stroke, shadow and particle colours. */
fun showColorPicker(
    activity: ComponentActivity,
    title: String,
    initial: Int,
    allowAuto: Boolean,
    onSelect: (Int?) -> Unit,
) {
    val column = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(8))
    }
    val swatch = View(activity).apply {
        contentDescription = "Selected colour preview"
        setBackgroundColor(initial)
    }
    column.addView(swatch, LinearLayout.LayoutParams(-1, activity.dp(48)))
    val field = EditText(activity).apply {
        setSingleLine(true)
        setTextColor(Pulse.TEXT)
        setHintTextColor(Pulse.MUTED)
        hint = "#RRGGBB or #AARRGGBB"
        contentDescription = "Hex colour, RGB or ARGB"
    }
    column.addView(field, activity.full(top = 8))
    val hsv = FloatArray(3)
    Color.colorToHSV(initial, hsv)
    var alpha = Color.alpha(initial)
    var updating = false

    fun control(label: String, max: Int, colors: IntArray): Pair<SeekBar, View> {
        column.addView(activity.uiText(label, 12f, Pulse.MUTED), activity.full(top = 8))
        val holder = FrameLayout(activity)
        val track = View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
                cornerRadius = activity.dp(4).toFloat()
            }
        }
        holder.addView(track, FrameLayout.LayoutParams(-1, activity.dp(8), Gravity.CENTER).apply {
            leftMargin = activity.dp(12); rightMargin = activity.dp(12)
        })
        val seek = SeekBar(activity).apply {
            this.max = max
            contentDescription = label
            progressTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            thumbTintList = ColorStateList.valueOf(Pulse.ACCENT_LIGHT)
        }
        holder.addView(seek, FrameLayout.LayoutParams(-1, activity.dp(42)))
        column.addView(holder)
        return seek to track
    }

    val (hue, _) = control("Hue spectrum", 360, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED))
    val (saturation, saturationTrack) = control("Saturation", 100, intArrayOf(Color.WHITE, Color.RED))
    val (brightness, brightnessTrack) = control("Brightness", 100, intArrayOf(Color.BLACK, Color.RED))
    val (opacity, opacityTrack) = control("Opacity", 255, intArrayOf(Color.BLACK, Color.WHITE))

    fun updateTracks() {
        val full = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        saturationTrack.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))))
        brightnessTrack.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))))
        opacityTrack.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.BLACK, full))
    }
    fun syncControls(color: Int, updateHex: Boolean) {
        updating = true
        Color.colorToHSV(color, hsv)
        alpha = Color.alpha(color)
        hue.progress = hsv[0].toInt()
        saturation.progress = (hsv[1] * 100).toInt()
        brightness.progress = (hsv[2] * 100).toInt()
        opacity.progress = alpha
        swatch.setBackgroundColor(color)
        updateTracks()
        if (updateHex) { field.setText(ColorHex.format(color)); field.setSelection(field.length()) }
        updating = false
    }
    fun fromControls() {
        if (updating) return
        hsv[0] = hue.progress.toFloat()
        hsv[1] = saturation.progress / 100f
        hsv[2] = brightness.progress / 100f
        alpha = opacity.progress
        val color = Color.HSVToColor(alpha, hsv)
        // Do not recalculate HSV here: retain the selected hue even at black or zero saturation.
        updating = true
        field.setText(ColorHex.format(color))
        field.setSelection(field.length())
        swatch.setBackgroundColor(color)
        updateTracks()
        updating = false
    }
    listOf(hue, saturation, brightness, opacity).forEach { seek ->
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) fromControls() }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }
    field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (!updating) ColorHex.parse(s?.toString().orEmpty())?.let { syncControls(it, false) }
        }
        override fun afterTextChanged(s: Editable?) = Unit
    })
    syncControls(initial, true)
    val scroll = ScrollView(activity).apply { addView(column) }
    val builder = AlertDialog.Builder(activity).setTitle(title).setView(scroll)
        .setPositiveButton("Use colour", null).setNegativeButton("Cancel", null)
    if (allowAuto) builder.setNeutralButton("Follow clock") { _, _ -> onSelect(null) }
    val dialog = builder.create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val color = ColorHex.parse(field.text.toString())
            if (color == null) field.error = "Enter 6 RGB or 8 ARGB hex digits"
            else { onSelect(color); dialog.dismiss() }
        }
    }
    dialog.show()
}
