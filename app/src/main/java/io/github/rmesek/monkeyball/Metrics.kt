package io.github.rmesek.monkeyball

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Debug
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Frame statistics over the game, for measuring on a phone without debugging tools (no USB, adb or
 * Profiler): the player reads them off the screen. Off unless the game was started from the app shortcut
 * "Play with metrics" (long press on the icon; res/xml/shortcuts.xml), so a normal start never shows them.
 *
 * The numbers are the engine's own (`PerfStats`, native/src/lib.rs, the same as the logcat `perf:` lines),
 * summed up every 5 s of play; the memory the app uses (PSS) is added here.
 */
internal class MetricsOverlay(private val activity: Activity) {
    private val view = TextView(activity).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0x99000000.toInt())
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setPadding(16, 8, 16, 8)
        setText(R.string.metrics_waiting)
    }

    /** At the bottom in the middle, where the game's HUD has nothing. */
    private val params = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)

    init {
        activity.addContentView(view, params)
    }

    /** What the overlay shows now (for the tests). */
    val text: String get() = view.text.toString()

    /** Keep clear of the space system bars and cutouts cover at the bottom. */
    fun keepAbove(bottomInset: Int) {
        params.bottomMargin = bottomInset
        view.layoutParams = params
    }

    /** A new summary from the game; any thread. */
    fun show(summary: String) = activity.runOnUiThread {
        // On the UI thread, not the game's: reading the memory takes a moment, and must not show up as a
        // slow frame in the very numbers it is shown with.
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        view.text = activity.getString(R.string.metrics_memory, summary, memory.totalPss / 1024)
    }

    companion object {
        /** The intent extra the shortcut sets. */
        const val EXTRA = "metrics"

        fun requested(intent: Intent?) = intent?.getBooleanExtra(EXTRA, false) == true
    }
}
