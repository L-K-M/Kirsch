package com.example.kirsch.archival

import android.graphics.BitmapFactory
import com.example.kirsch.scan.ScanManifestStore
import java.io.File
import java.time.Instant
import org.json.JSONObject

object ArchivalMetadataStore {
    fun record(
        scanManifest: File,
        physicalWidthMm: Double,
        physicalHeightMm: Double,
        authority: ScaleAuthority,
        targetId: String?,
    ): ScaleMeasurement = ScanManifestStore.locked {
        val manifest = JSONObject(scanManifest.readText())
        require(manifest.getString("state") == "review") { "Accepted scans are immutable; start a new revision to edit" }
        val root = requireNotNull(scanManifest.parentFile)
        val preview = File(root, manifest.getString("preview_path"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(preview.absolutePath, bounds)
        val measurement = ScaleMeasurement(
            pixelWidth = bounds.outWidth,
            pixelHeight = bounds.outHeight,
            physicalWidthMm = physicalWidthMm,
            physicalHeightMm = physicalHeightMm,
            authority = authority,
            targetId = targetId,
        )
        manifest.put(
            "archival_scale",
            JSONObject()
                .put("recorded_utc", Instant.now().toString())
                .put("authority", authority.manifestValue)
                .put("target_id", targetId)
                .put("physical_width_mm", physicalWidthMm)
                .put("physical_height_mm", physicalHeightMm)
                .put("pixel_width", measurement.pixelWidth)
                .put("pixel_height", measurement.pixelHeight)
                .put("sampling_frequency_ppi_x", measurement.ppiX)
                .put("sampling_frequency_ppi_y", measurement.ppiY)
                .put("claim", "sampling-frequency-from-confirmed-physical-scale")
                .put("delivered_resolution_claimed", false),
        )
        ScanManifestStore.write(scanManifest, manifest)
        measurement
    }
}
