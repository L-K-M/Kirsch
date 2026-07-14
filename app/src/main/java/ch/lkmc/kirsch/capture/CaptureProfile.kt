package ch.lkmc.kirsch.capture

enum class CaptureProfile(
    val preferredMode: CaptureMode,
    val frameCount: Int,
    val frameIntervalNs: Long,
    val manifestValue: String,
    val displayName: String,
    val sweep: Boolean = false,
) {
    /**
     * Default product path: a displacement-driven freehand sweep. The frame
     * count is the per-sweep maximum; SweepPolicy decides which frames are
     * kept and when enough view displacement has been gathered (the
     * 2026-07-14 GRL-AL10 audit showed fixed-count bursts do not enforce the
     * geometry fusion needs).
     */
    SWEEP(
        CaptureMode.YUV,
        frameCount = 18,
        frameIntervalNs = 0L,
        manifestValue = "product-sweep",
        displayName = "Glare-removal sweep",
        sweep = true,
    ),
    QUALITY_YUV(
        CaptureMode.YUV,
        frameCount = 9,
        frameIntervalNs = 125_000_000L,
        manifestValue = "quality-yuv-sweep",
        displayName = "Fixed 9-frame burst",
    ),
    QUALITY_RAW(
        CaptureMode.RAW,
        frameCount = 9,
        frameIntervalNs = 125_000_000L,
        manifestValue = "quality-raw-sweep",
        displayName = "RAW acquisition only",
    ),
    QUICK_SINGLE(
        CaptureMode.YUV,
        frameCount = 1,
        frameIntervalNs = 0L,
        manifestValue = "quick-single",
        displayName = "Quick single frame",
    ),
}
