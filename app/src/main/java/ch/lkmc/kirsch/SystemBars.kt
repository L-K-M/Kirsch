package ch.lkmc.kirsch

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * Edge-to-edge window handling.
 *
 * An app targeting API 35 is drawn edge to edge on Android 15 whether it asks
 * or not: `windowDrawsSystemBarBackgrounds` is forced on, and the theme's
 * `statusBarColor`/`navigationBarColor` are ignored. Without explicit inset
 * handling, content lands under the status bar, the display cutout, and the
 * gesture bar.
 *
 * [optIn] turns the same behavior on for every supported release rather than
 * only Android 15, so the layout is identical everywhere and the insets a view
 * receives are never already applied by the decor. The camera preview also
 * genuinely wants the whole window; only the chrome on top of it needs to move.
 */
object SystemBars {
    fun optIn(activity: Activity) {
        activity.window.setDecorFitsSystemWindows(false)
    }

    /**
     * Adds the system-bar and display-cutout insets to [view]'s existing
     * padding on the requested edges. Insets are passed on unconsumed, so
     * descendant views still receive them in full.
     *
     * Call once per view, before insets have been dispatched to it —
     * typically while building the layout. The view's padding at call time
     * becomes the baseline the insets are added to, so calling this again
     * after a dispatch would take already-inset padding as the new baseline
     * and double it. For the same reason, do not nest padded views: because
     * insets are passed on unconsumed, a descendant that also calls this
     * applies them a second time.
     */
    fun pad(
        view: View,
        left: Boolean = false,
        top: Boolean = false,
        right: Boolean = false,
        bottom: Boolean = false,
        includeIme: Boolean = false,
    ) {
        val startLeft = view.paddingLeft
        val startTop = view.paddingTop
        val startRight = view.paddingRight
        val startBottom = view.paddingBottom
        // Fixed for the life of the listener, which fires on every IME
        // animation frame, rotation, and multi-window change.
        val types = WindowInsets.Type.systemBars() or
            WindowInsets.Type.displayCutout() or
            if (includeIme) WindowInsets.Type.ime() else 0
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(types)
            target.setPadding(
                startLeft + if (left) bars.left else 0,
                startTop + if (top) bars.top else 0,
                startRight + if (right) bars.right else 0,
                startBottom + if (bottom) bars.bottom else 0,
            )
            insets
        }
        view.requestApplyInsets()
    }

    /**
     * Same, for a view whose own padding is part of its appearance — a pill
     * background would deform if the inset were added to it — so the offset
     * goes into the layout margin instead.
     */
    fun offsetTopMargin(view: View) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (params == null) {
            // Silently doing nothing would leave the view under the status
            // bar with no trace. Throwing would take the camera screen down
            // over a layout-params mismatch, which is worse than a view that
            // sits too high, so this is loud in logcat and harmless on screen.
            Log.w(
                "Kirsch",
                "offsetTopMargin needs MarginLayoutParams, got " +
                    "${view.layoutParams?.javaClass?.simpleName}; inset not applied",
            )
            return
        }
        val startTop = params.topMargin
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            (target.layoutParams as? ViewGroup.MarginLayoutParams)?.let { current ->
                if (current.topMargin != startTop + bars.top) {
                    current.topMargin = startTop + bars.top
                    target.requestLayout()
                }
            }
            insets
        }
        view.requestApplyInsets()
    }
}
