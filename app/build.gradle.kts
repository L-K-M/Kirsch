plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Base plugin id: the Android-specific one needs AGP 8.13. The generated
    // definitions are wired into res below.
    id("com.mikepenz.aboutlibraries.plugin")
}

android {
    namespace = "ch.lkmc.kirsch"
    compileSdk = 35
    useLibrary("android.test.runner")
    useLibrary("android.test.base")

    defaultConfig {
        applicationId = "ch.lkmc.kirsch"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            res.srcDir(layout.buildDirectory.dir("generated/aboutLibraries/res"))
        }
    }
}

// Third-party notices (res/raw/aboutlibraries.json, shown by LicensesActivity):
// offline and reproducible, from the resolved dependency graph plus
// config/libraries and config/licenses, which hold the native code OpenCV
// links statically and NDK libc++. Fails on a dependency without a license or
// with one outside the allow-list.
aboutLibraries {
    offlineMode = true
    collect {
        configPath = file("config")
        fetchRemoteLicense = false
        fetchRemoteFunding = false
    }
    export {
        outputFile = layout.buildDirectory.file("generated/aboutLibraries/res/raw/aboutlibraries.json")
    }
    library {
        requireLicense = true
    }
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll(
            "Apache-2.0",
            "Apache-2.0 WITH LLVM-exception",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "IJG",
            "libpng-2.0",
            "libtiff",
            "MIT",
        )
        // Intel IPP ICV in OpenCV's x86 libraries: proprietary terms, allowed for it alone.
        allowedLicensesMap.put("LicenseRef-Intel-ISSL", listOf("kirsch:intel-ipp-icv"))
    }
}

tasks.named("preBuild") { dependsOn("exportLibraryDefinitions") }

dependencies {
    implementation("org.opencv:opencv:5.0.0.1")
    testImplementation("junit:junit:4.13.2")
}
