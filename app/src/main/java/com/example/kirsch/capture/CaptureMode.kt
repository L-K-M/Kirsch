package com.example.kirsch.capture

enum class CaptureMode(
    val manifestValue: String,
    val displayName: String,
) {
    RAW("raw-sensor", "RAW/DNG"),
    YUV("yuv-420-888", "YUV/I420"),
}
