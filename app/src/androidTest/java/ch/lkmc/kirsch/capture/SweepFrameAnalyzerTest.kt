package ch.lkmc.kirsch.capture

import android.test.AndroidTestCase
import java.nio.ByteBuffer
import kotlin.random.Random
import org.opencv.android.OpenCVLoader

@Suppress("DEPRECATION")
class SweepFrameAnalyzerTest : AndroidTestCase() {
    override fun setUp() {
        super.setUp()
        assertTrue(OpenCVLoader.initLocal())
    }

    fun testFlatSceneDoesNotReportReliableMotion() {
        val analyzer = SweepFrameAnalyzer()
        val luma = ByteBuffer.wrap(ByteArray(256 * 192) { 128.toByte() })
        try {
            analyzer.measureLuma(luma, 256, 1, 0, 0, 256, 192)
            val stationary = analyzer.measureLuma(luma, 256, 1, 0, 0, 256, 192)
            assertTrue("textureless response=${stationary.trackingResponse}", stationary.trackingResponse < 0.1)
        } finally {
            analyzer.release()
        }
    }

    fun testTranslatedNativeFrameUsesTheDeclaredAnalysisWidth() {
        val width = 4032
        val height = 3024
        val texture = Random(7910).nextBytes(256 * 192)
        fun frame(shift: Int) = ByteBuffer.wrap(ByteArray(width * height) { index ->
            val x = ((index % width - shift + width) % width) * 256 / width
            val y = index / width * 192 / height
            texture[y * 256 + x]
        })
        val analyzer = SweepFrameAnalyzer()
        try {
            analyzer.measureLuma(frame(0), width, 1, 0, 0, width, height)
            // 126 / 4032 * 256 = 8 analysis pixels. Integer-step decimation
            // used to produce 268 pixels while the policy assumed 256.
            val shifted = analyzer.measureLuma(frame(126), width, 1, 0, 0, width, height)
            assertTrue("tracking response=${shifted.trackingResponse}", shifted.trackingResponse > 0.5)
            assertEquals(8.0, shifted.shiftX, 0.25)
            assertEquals(0.0, shifted.shiftY, 0.25)
        } finally {
            analyzer.release()
        }
    }
}
