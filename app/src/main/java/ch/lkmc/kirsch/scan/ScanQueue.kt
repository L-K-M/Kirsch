package ch.lkmc.kirsch.scan

import android.content.Context
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import org.json.JSONObject

object ScanQueue {
    interface Listener {
        fun onScanQueued(captureId: String)
        fun onScanReady(result: ScanProcessor.Result)
        fun onScanFailed(captureId: String, error: Throwable)
    }

    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "kirsch-scan-queue") }
    private val pending = mutableSetOf<String>()

    fun enqueue(context: Context, captureManifest: File, listener: Listener? = null) {
        val captureId = captureManifest.parentFile?.name ?: captureManifest.absolutePath
        synchronized(pending) {
            if (!pending.add(captureId)) return
        }
        listener?.onScanQueued(captureId)
        executor.execute {
            try {
                val processor = ScanProcessor(context.applicationContext)
                runCatching { processor.process(captureManifest) }
                    .onSuccess { runCatching { listener?.onScanReady(it) } }
                    .onFailure {
                        runCatching { processor.markFailed(captureManifest, it) }
                        runCatching { listener?.onScanFailed(captureId, it) }
                    }
            } finally {
                synchronized(pending) { pending.remove(captureId) }
            }
        }
    }

    fun resumePending(context: Context, listener: Listener? = null) {
        // Walking every capture directory and parsing its manifests is file
        // I/O; callers invoke this from the main thread (Activity.onResume),
        // so the scan happens on the queue's own worker. The application
        // context replaces the caller's, and the listener is held weakly:
        // queued processing can run for minutes, and callbacks were already
        // best-effort (a recreated Activity re-subscribes on its own resume).
        val applicationContext = context.applicationContext
        val weakListener = listener?.let(::WeakReference)
        executor.execute {
            val scanRoot = ScanProcessor(applicationContext).scanRoot()
            val captures = applicationContext.getExternalFilesDir("captures")
                ?: File(applicationContext.filesDir, "captures")
            captures.listFiles { file -> file.isDirectory && File(file, "capture.json").isFile }
                ?.sortedBy(File::getName)
                ?.forEach { directory ->
                    val acceptedCapture = runCatching {
                        JSONObject(File(directory, "capture.json").readText()).let {
                            it.optString("status") == "accepted" && it.optString("mode") == "yuv-420-888"
                        }
                    }.getOrDefault(false)
                    if (!acceptedCapture) return@forEach
                    val scan = File(scanRoot, "${directory.name}/scan.json")
                    val completed = scan.isFile && runCatching {
                        val state = JSONObject(scan.readText()).optString("state")
                        state == "review" || state == "accepted" || state == "failed"
                    }.getOrDefault(false)
                    if (!completed) {
                        enqueue(applicationContext, File(directory, "capture.json"), weakListener?.get())
                    }
                }
        }
    }
}
