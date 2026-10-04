package ch.lkmc.kirsch

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.test.InstrumentationTestCase
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.TextView
import ch.lkmc.kirsch.capture.Camera2BurstController
import ch.lkmc.kirsch.scan.ScanProcessor
import ch.lkmc.kirsch.scan.ScanQueue
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Exercises capture callbacks and navigation on a real Activity without camera timing. */
@Suppress("DEPRECATION")
class MainActivityTest : InstrumentationTestCase() {
    private lateinit var activity: MainActivity
    private lateinit var controller: Camera2BurstController
    private lateinit var directory: File
    private lateinit var reviewMonitor: Instrumentation.ActivityMonitor
    private val scanDirectories = mutableListOf<File>()

    override fun setUp() {
        super.setUp()
        val context = instrumentation.targetContext
        directory = File(context.cacheDir, "main-callback-test-${UUID.randomUUID()}")
        check(directory.mkdirs())
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        }
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.waitForIdleSync()

        val original = field("controller").get(activity) as Camera2BurstController
        original.shutdown()
        check(cameraThread(original).let { thread -> thread.join(5000); !thread.isAlive })
        instrumentation.waitForIdleSync()
        onMain {
            // An unattached preview keeps the real controller idle. Tests hold
            // its handler to reproduce reservation/callback order precisely.
            controller = Camera2BurstController(context, TextureView(context), activity)
            field("controller").set(activity, controller)
        }
        reviewMonitor = instrumentation.addMonitor(ReviewActivity::class.java.name, null, true)
    }

    override fun tearDown() {
        if (::reviewMonitor.isInitialized) instrumentation.removeMonitor(reviewMonitor)
        if (::activity.isInitialized) onMain { activity.finish() }
        instrumentation.waitForIdleSync()
        if (::controller.isInitialized) {
            controller.shutdown()
            cameraThread(controller).join(5000)
        }
        awaitQueueIdle()
        scanDirectories.forEach(File::deleteRecursively)
        if (::directory.isInitialized) directory.deleteRecursively()
        super.tearDown()
    }

    fun testStartingScanReservesIdentityBeforeCameraHandlerRuns() {
        val release = blockCameraHandler()
        var reserved: String? = null
        var nextReservation: Any? = null
        try {
            onMain {
                field("pendingReviewScanId").set(activity, "older-request")
                val start = MainActivity::class.java.getDeclaredMethod("startScan").apply { isAccessible = true }
                start.invoke(activity)
                reserved = field("pendingReviewScanId").get(activity) as String?
                nextReservation = controller.capture("reservation-test", Surface.ROTATION_0)
            }
            assertNotNull("The shutter must reserve its review identity before the camera handler runs", reserved)
            assertTrue("The latest request must replace the old identity", reserved != "older-request")
            assertTrue("capture must return its reservation synchronously", nextReservation is String)
            assertTrue("Each request must reserve a distinct capture", nextReservation != reserved)
            onMain { assertFalse(field("capturing").getBoolean(activity)) }
        } finally {
            release.countDown()
            awaitCameraIdle()
        }
        // A reservation is a request identity, not a successful capture. An
        // unstarted camera must still report its ordinary failure and stay idle.
        onMain {
            assertEquals("Camera is not ready", (field("statusChip").get(activity) as TextView).text.toString())
            assertFalse(field("capturing").getBoolean(activity))
            assertEquals(reserved, field("pendingReviewScanId").get(activity))
        }
        assertEquals(0, reviewMonitor.hits)
    }

    fun testOlderCompletionCannotReplaceLatestPendingReview() {
        val result = readyScan()
        val capture = File(File(directory, result.manifest.parentFile!!.name).apply { mkdirs() }, "capture.json")
        capture.writeText(JSONObject().put("capture_id", result.manifest.parentFile!!.name)
            .put("status", "accepted").put("mode", "yuv-420-888").toString())
        val release = blockQueue()
        try {
            onMain { field("pendingReviewScanId").set(activity, "latest-request") }
            // The older camera callback arrives after the newer request. Use
            // the actual JSON reader and queue, not a synthetic pending update.
            activity.onCaptureFinished(capture.absolutePath)
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals("latest-request", field("pendingReviewScanId").get(activity))
            }
        } finally {
            release.countDown()
            awaitQueueIdle()
        }
        assertEquals("The older scan must remain in the library without interrupting the new request", 0, reviewMonitor.hits)
        assertTrue(result.manifest.isFile)
    }

    fun testReadyScanDoesNotInterruptActiveCapture() {
        val result = readyScan()
        onMain {
            field("pendingReviewScanId").set(activity, result.manifest.parentFile!!.name)
            activity.onBusyChanged(true)
            activity.onScanReady(result)
            assertFalse((field("shutterButton").get(activity) as View).isEnabled)
            assertEquals(result.manifest.parentFile!!.name, field("pendingReviewScanId").get(activity))
        }
        assertEquals("Review must not pause the camera during an active capture", 0, reviewMonitor.hits)
    }

    fun testIdleMatchingScanOpensReview() {
        val result = readyScan()
        onMain {
            field("pendingReviewScanId").set(activity, result.manifest.parentFile!!.name)
            activity.onBusyChanged(false)
            activity.onScanReady(result)
            assertNull(field("pendingReviewScanId").get(activity))
        }
        assertEquals("The latest scan must still open automatically while idle", 1, reviewMonitor.hits)
    }

    private fun readyScan(): ScanProcessor.Result {
        val scan = File(ScanProcessor(instrumentation.targetContext).scanRoot(), "callback-test-${UUID.randomUUID()}")
        check(scan.mkdirs())
        scanDirectories += scan
        val preview = File(scan, "preview.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            preview.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val manifest = File(scan, "scan.json")
        manifest.writeText(JSONObject().put("scan_id", scan.name).put("state", "review")
            .put("preview_path", preview.name).put("used_fusion", false).toString())
        return ScanProcessor.Result(manifest, preview, false)
    }

    private fun blockCameraHandler(): CountDownLatch = block { task -> cameraHandler().post(task) }

    private fun blockQueue(): CountDownLatch = block { task -> queueExecutor().execute(task) }

    private fun block(schedule: (Runnable) -> Unit): CountDownLatch {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        schedule(Runnable {
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        })
        check(started.await(5, TimeUnit.SECONDS))
        return release
    }

    private fun awaitCameraIdle() {
        val complete = CountDownLatch(1)
        cameraHandler().post { complete.countDown() }
        check(complete.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun awaitQueueIdle() {
        val complete = CountDownLatch(1)
        queueExecutor().execute { complete.countDown() }
        check(complete.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun cameraHandler(): Handler = Camera2BurstController::class.java.getDeclaredField("cameraHandler")
        .apply { isAccessible = true }.get(controller) as Handler

    private fun cameraThread(value: Camera2BurstController): HandlerThread =
        Camera2BurstController::class.java.getDeclaredField("cameraThread").apply { isAccessible = true }.get(value) as HandlerThread

    private fun queueExecutor(): ExecutorService = ScanQueue::class.java.getDeclaredField("executor")
        .apply { isAccessible = true }.get(null) as ExecutorService

    private fun onMain(action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
