package ch.lkmc.kirsch

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewViewportTest {
    @Test
    fun fitsTheWholePhotoWithoutDistortion() {
        val viewport = PreviewViewport()
        viewport.configure(1000f, 500f, 500f, 500f)

        assertEquals(0f, viewport.bounds.left, 0.01f)
        assertEquals(125f, viewport.bounds.top, 0.01f)
        assertEquals(500f, viewport.bounds.width, 0.01f)
        assertEquals(250f, viewport.bounds.height, 0.01f)
    }

    @Test
    fun zoomKeepsTheInspectedPixelUnderTheFinger() {
        val viewport = PreviewViewport()
        viewport.configure(1000f, 500f, 500f, 500f)
        val pixelBefore = (400f - viewport.bounds.left) / viewport.bounds.width

        viewport.zoomBy(4f, 400f, 250f)

        val pixelAfter = (400f - viewport.bounds.left) / viewport.bounds.width
        assertEquals(pixelBefore, pixelAfter, 0.001f)
    }

    @Test
    fun panStopsAtPhotoEdgesAndCentersDimensionsThatFit() {
        val viewport = PreviewViewport()
        viewport.configure(1000f, 500f, 500f, 500f)
        viewport.zoomBy(2f, 250f, 250f)

        viewport.panBy(10000f, 10000f)
        assertEquals(0f, viewport.bounds.left, 0.01f)
        assertEquals(0f, viewport.bounds.top, 0.01f)

        viewport.panBy(-20000f, -20000f)
        assertEquals(500f, viewport.bounds.right, 0.01f)
        assertEquals(500f, viewport.bounds.bottom, 0.01f)
    }

    @Test
    fun zoomHasBoundsAndResetReturnsToTheWholePhoto() {
        val viewport = PreviewViewport()
        viewport.configure(1000f, 500f, 500f, 500f)
        viewport.zoomBy(1000f, 250f, 250f)
        assertEquals(8f, viewport.zoom, 0.01f)

        viewport.zoomBy(0.0001f, 250f, 250f)
        assertEquals(1f, viewport.zoom, 0.01f)

        viewport.zoomBy(4f, 250f, 250f)
        viewport.panBy(90f, 70f)
        viewport.reset()
        assertEquals(1f, viewport.zoom, 0.01f)
        assertEquals(0f, viewport.bounds.left, 0.01f)
        assertEquals(125f, viewport.bounds.top, 0.01f)
    }
}
