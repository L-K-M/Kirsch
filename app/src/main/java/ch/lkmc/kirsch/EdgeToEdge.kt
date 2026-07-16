package ch.lkmc.kirsch

import android.graphics.Insets
import android.os.Build
import android.view.Window
import android.view.WindowInsets

/**
 * Uniform edge-to-edge behavior across supported API levels. Android 15
 * (API 35) enforces edge-to-edge for apps targeting SDK 35 and ignores the
 * theme's bar colors, so content draws behind the system bars there whether
 * handled or not. Opting in on older releases too gives every device the
 * same contract: the window draws behind the bars and each screen pads its
 * own content with the reported insets.
 */
object EdgeToEdge {
    fun apply(window: Window) {
        if (Build.VERSION.SDK_INT < 35) {
            // Deprecated in 35, where the platform forces this behavior.
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        }
    }

    fun systemBarInsets(insets: WindowInsets): Insets =
        insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
}
