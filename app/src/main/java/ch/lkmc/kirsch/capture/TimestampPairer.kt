package ch.lkmc.kirsch.capture

/**
 * Pairs asynchronous image and CaptureResult callbacks by exact sensor
 * timestamp. Pending results are bounded too: during a sweep most frames are
 * analyzed and discarded without ever producing an image, so their results
 * must not accumulate.
 */
class TimestampPairer<I : Any, R : Any>(
    private val maxPendingImages: Int,
    private val onPair: (I, R) -> Unit,
    private val onDropImage: (I) -> Unit,
    private val maxPendingResults: Int = maxPendingImages * 8,
) {
    private val images = linkedMapOf<Long, I>()
    private val results = linkedMapOf<Long, R>()

    init {
        require(maxPendingImages > 0 && maxPendingResults > 0)
    }

    fun addImage(timestampNs: Long, image: I) {
        var pair: Pair<I, R>? = null
        val dropped = mutableListOf<I>()
        synchronized(this) {
            val result = results.remove(timestampNs)
            if (result != null) {
                pair = image to result
            } else {
                images.put(timestampNs, image)?.let(dropped::add)
                while (images.size > maxPendingImages) {
                    val oldest = images.entries.first()
                    images.remove(oldest.key)
                    dropped += oldest.value
                }
            }
        }
        dropped.forEach(onDropImage)
        pair?.let { onPair(it.first, it.second) }
    }

    fun addResult(timestampNs: Long, result: R) {
        var pair: Pair<I, R>? = null
        synchronized(this) {
            val image = images.remove(timestampNs)
            if (image != null) {
                pair = image to result
            } else {
                results[timestampNs] = result
                while (results.size > maxPendingResults) {
                    results.remove(results.keys.first())
                }
            }
        }
        pair?.let { onPair(it.first, it.second) }
    }

    @Synchronized
    fun pendingCounts(): Pair<Int, Int> = images.size to results.size

    fun clear() {
        val dropped: List<I>
        synchronized(this) {
            dropped = images.values.toList()
            images.clear()
            results.clear()
        }
        dropped.forEach(onDropImage)
    }
}
