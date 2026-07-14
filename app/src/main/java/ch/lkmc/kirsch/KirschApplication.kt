package ch.lkmc.kirsch

import android.app.Application
import org.opencv.android.OpenCVLoader

class KirschApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        check(OpenCVLoader.initLocal()) { "Unable to initialize the bundled OpenCV runtime" }
    }
}
