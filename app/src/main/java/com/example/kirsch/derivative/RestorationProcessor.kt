package com.example.kirsch.derivative

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

enum class RestorationRecipe(val id: String, val label: String) {
    DESCREEN("descreen", "Descreen"),
    DUST_SCRATCH("dust-scratch", "Dust / scratch"),
    FADE("fade", "Fade correction"),
    CLASSICAL_UPSCALE("classical-upscale", "2x classical upscale"),
}

object RestorationProcessor {
    fun apply(source: Mat, recipe: RestorationRecipe): Mat {
        FeatureCatalog.requireAvailable(recipe.id)
        return when (recipe) {
            RestorationRecipe.DESCREEN -> descreen(source)
            RestorationRecipe.DUST_SCRATCH -> dustAndScratch(source)
            RestorationRecipe.FADE -> correctFade(source)
            RestorationRecipe.CLASSICAL_UPSCALE -> Mat().also {
                Imgproc.resize(source, it, Size(), 2.0, 2.0, Imgproc.INTER_LANCZOS4)
            }
        }
    }

    private fun descreen(source: Mat): Mat {
        val smoothed = Mat()
        Imgproc.bilateralFilter(source, smoothed, 7, 22.0, 5.0)
        val lowFrequency = Mat()
        Imgproc.GaussianBlur(smoothed, lowFrequency, Size(0.0, 0.0), 1.1)
        val output = Mat()
        Core.addWeighted(smoothed, 1.12, lowFrequency, -0.12, 0.0, output)
        smoothed.release()
        lowFrequency.release()
        return output
    }

    private fun dustAndScratch(source: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
        val median = Mat()
        Imgproc.medianBlur(gray, median, 5)
        val difference = Mat()
        Core.absdiff(gray, median, difference)
        val mask = Mat()
        Imgproc.threshold(difference, mask, 24.0, 255.0, Imgproc.THRESH_BINARY)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)
        val output = Mat()
        Photo.inpaint(source, mask, output, 2.0, Photo.INPAINT_TELEA)
        gray.release()
        median.release()
        difference.release()
        mask.release()
        kernel.release()
        return output
    }

    private fun correctFade(source: Mat): Mat {
        val lab = Mat()
        Imgproc.cvtColor(source, lab, Imgproc.COLOR_BGR2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)
        val correctedLightness = Mat()
        Imgproc.createCLAHE(1.6, Size(8.0, 8.0)).apply(channels[0], correctedLightness)
        channels[0].release()
        channels[0] = correctedLightness
        Core.merge(channels, lab)
        val output = Mat()
        Imgproc.cvtColor(lab, output, Imgproc.COLOR_Lab2BGR)
        channels.forEach(Mat::release)
        lab.release()
        return output
    }
}
