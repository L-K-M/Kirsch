package com.example.kirsch.capture

enum class CaptureProfile(
    val preferredMode: CaptureMode,
    val frameCount: Int,
    val frameIntervalNs: Long,
    val manifestValue: String,
    val displayName: String,
) {
    QUALITY_YUV(
        CaptureMode.YUV,
        frameCount = 9,
        frameIntervalNs = 125_000_000L,
        manifestValue = "quality-yuv-sweep",
        displayName = "Quality YUV sweep",
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
