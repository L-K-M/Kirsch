package ch.lkmc.kirsch

import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import org.opencv.core.Point

/** Keeps live review work independent of an Activity's lifetime. */
internal object ReviewOperations {
    enum class DraftPolicy { PRESERVE, RESET }
    enum class Kind { DERIVATIVE, SAVE }

    interface Listener {
        fun onReviewOperationChanged()
    }

    data class Snapshot(
        val id: Long,
        val message: String,
        val draft: List<Point>,
        val draftPolicy: DraftPolicy,
        val kind: Kind,
        val result: Result<File>? = null,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextId = AtomicLong()
    private val lock = Any()
    private val operations = mutableMapOf<String, Snapshot>()
    private val listeners = mutableMapOf<String, WeakReference<Listener>>()

    fun bind(manifest: File, listener: Listener) = synchronized(lock) {
        listeners[manifest.absolutePath] = WeakReference(listener)
    }

    fun unbind(manifest: File, listener: Listener) = synchronized(lock) {
        val key = manifest.absolutePath
        if (listeners[key]?.get() === listener) listeners.remove(key)
    }

    fun snapshot(manifest: File): Snapshot? = synchronized(lock) {
        operations[manifest.absolutePath]
    }

    fun start(
        manifest: File,
        message: String,
        draft: List<Point>,
        draftPolicy: DraftPolicy,
        kind: Kind,
        operation: () -> File,
    ): Boolean {
        val key = manifest.absolutePath
        val running = synchronized(lock) {
            if (operations.containsKey(key)) return false
            Snapshot(nextId.incrementAndGet(), message, draft.map { Point(it.x, it.y) }, draftPolicy, kind)
                .also { operations[key] = it }
        }
        // Operations capture only immutable arguments and application services.
        // Completion looks up the current weak listener, including a replacement
        // Activity, and stays available until that screen displays the result.
        Thread({
            val result = runCatching(operation)
            synchronized(lock) { operations[key] = running.copy(result = result) }
            mainHandler.post {
                val listener = synchronized(lock) { listeners[key]?.get() }
                listener?.onReviewOperationChanged()
            }
        }, "kirsch-review-operation").start()
        return true
    }

    fun acknowledge(manifest: File, id: Long) = synchronized(lock) {
        val key = manifest.absolutePath
        val current = operations[key]
        if (current?.id == id && current.result != null) operations.remove(key)
    }
}
