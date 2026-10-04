package ch.lkmc.kirsch.imaging

/** Bounds native image memory while preserving the sweep's measured viewpoints. */
internal object CaptureFrameSelection {
    data class Observation(val x: Double, val y: Double, val sharpness: Double)

    fun positions(count: Int, maximum: Int, observations: List<Observation>? = null): List<Int> {
        require(count >= 0 && maximum >= 2)
        if (count <= maximum) return (0 until count).toList()
        if (observations == null || observations.size != count || observations.any {
                !it.x.isFinite() || !it.y.isFinite() || !it.sharpness.isFinite() || it.sharpness < 0
            }) return evenlySpaced(count, maximum)
        val bestSharpness = observations.maxOf { it.sharpness }
        val candidates = observations.indices.filter { observations[it].sharpness >= bestSharpness * 0.5 }
        val selected = linkedSetOf<Int>()
        val origin = observations[0]
        selected += candidates.minWith(compareBy<Int> { distance(observations[it], origin) }
            .thenByDescending { observations[it].sharpness }.thenBy { it })
        val extremes = listOf(
            candidates.maxBy { observations[it].x }, candidates.minBy { observations[it].x },
            candidates.maxBy { observations[it].y }, candidates.minBy { observations[it].y },
        )
        extremes.forEach { if (selected.size < maximum) selected += it }
        while (selected.size < minOf(maximum, candidates.size)) {
            val remaining = candidates.filter { it !in selected }
            selected += remaining.maxWith(compareBy<Int> { candidate ->
                selected.minOf { distance(observations[candidate], observations[it]) }
            }.thenBy { observations[it].sharpness }.thenBy { -it })
        }
        return selected.sorted()
    }

    private fun evenlySpaced(count: Int, maximum: Int): List<Int> =
        (0 until maximum).map { index -> index * (count - 1) / (maximum - 1) }.distinct()

    private fun distance(first: Observation, second: Observation): Double {
        val x = first.x - second.x
        val y = first.y - second.y
        return x * x + y * y
    }
}
