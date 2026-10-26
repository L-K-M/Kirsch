package ch.lkmc.kirsch

import android.app.Activity
import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.ViewGroup
import android.widget.ListView

/** The licenses screen reads the generated definitions that ship in the APK. */
@Suppress("DEPRECATION")
class LicensesActivityTest : InstrumentationTestCase() {
    private var activity: Activity? = null

    override fun tearDown() {
        activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        instrumentation.waitForIdleSync()
        super.tearDown()
    }

    fun testListsGradleAndNativeComponents() {
        val context = instrumentation.targetContext
        activity = instrumentation.startActivitySync(
            Intent(context, LicensesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()

        var titles = emptyList<String>()
        instrumentation.runOnMainSync {
            val content = requireNotNull(activity).findViewById<ViewGroup>(android.R.id.content)
            val list = content.getChildAt(0) as ListView
            // The header row has no item; every other row is one component.
            titles = (0 until list.adapter.count).mapNotNull { list.adapter.getItem(it)?.toString() }
        }

        // Maven components, a library OpenCV links statically and the NDK runtime.
        for (expected in listOf("OpenCV", "Kotlin Stdlib", "libpng", "LLVM libc++")) {
            assertTrue("$expected missing from $titles", titles.any { it.startsWith(expected) })
        }
    }
}
