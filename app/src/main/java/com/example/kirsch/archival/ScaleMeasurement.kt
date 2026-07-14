package com.example.kirsch.archival

enum class ScaleAuthority(val manifestValue: String) {
    CONFIRMED_DIMENSIONS("confirmed-dimensions"),
    COPLANAR_TARGET("coplanar-target"),
}

data class ScaleMeasurement(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val physicalWidthMm: Double,
    val physicalHeightMm: Double,
    val authority: ScaleAuthority,
    val targetId: String?,
) {
    init {
        require(pixelWidth > 0 && pixelHeight > 0)
        require(physicalWidthMm > 0 && physicalHeightMm > 0)
        require(authority != ScaleAuthority.COPLANAR_TARGET || !targetId.isNullOrBlank()) {
            "A coplanar target measurement requires a target ID"
        }
    }

    val ppiX: Double = pixelWidth * 25.4 / physicalWidthMm
    val ppiY: Double = pixelHeight * 25.4 / physicalHeightMm
}
