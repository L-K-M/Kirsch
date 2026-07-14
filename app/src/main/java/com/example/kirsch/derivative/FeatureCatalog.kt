package com.example.kirsch.derivative

enum class FeatureAvailability { AVAILABLE, UNAVAILABLE }

data class OptionalFeature(
    val id: String,
    val label: String,
    val availability: FeatureAvailability,
    val reason: String? = null,
)

object FeatureCatalog {
    val features = listOf(
        OptionalFeature("residual-deglare", "Temporal residual de-glare", FeatureAvailability.AVAILABLE),
        OptionalFeature("descreen", "Descreening", FeatureAvailability.AVAILABLE),
        OptionalFeature("dust-scratch", "Dust and scratch removal", FeatureAvailability.AVAILABLE),
        OptionalFeature("fade", "Fade correction", FeatureAvailability.AVAILABLE),
        OptionalFeature("classical-upscale", "Classical 2x upscale", FeatureAvailability.AVAILABLE),
        OptionalFeature(
            "mfsr",
            "Multi-frame super-resolution",
            FeatureAvailability.UNAVAILABLE,
            "No mobile MFSR implementation has passed the recorded SFR and fidelity gates",
        ),
        OptionalFeature(
            "curl-correction",
            "Print curl correction",
            FeatureAvailability.UNAVAILABLE,
            "No print-specific mesh estimator or approved checkpoint is present",
        ),
        OptionalFeature(
            "album-segmentation",
            "Album page splitting",
            FeatureAvailability.UNAVAILABLE,
            "The repository has no approved instance model or patent disposition",
        ),
        OptionalFeature(
            "back-htr",
            "Back-of-photo handwriting",
            FeatureAvailability.UNAVAILABLE,
            "No camera-image handwriting model is present; confirmation cannot make a missing recognizer valid",
        ),
        OptionalFeature(
            "learned-illuminant",
            "Learned illuminant estimation",
            FeatureAvailability.UNAVAILABLE,
            "No C5/CCMNet-class checkpoint has an approved artifact record",
        ),
        OptionalFeature(
            "generative-restoration",
            "Generative restoration",
            FeatureAvailability.UNAVAILABLE,
            "No approved weights or identity-drift gate implementation is present",
        ),
        OptionalFeature(
            "c2pa",
            "C2PA credentials",
            FeatureAvailability.UNAVAILABLE,
            "No signing identity, certificate lifecycle, timestamping, or revocation service is configured",
        ),
        OptionalFeature(
            "cloud",
            "Attested cloud processing",
            FeatureAvailability.UNAVAILABLE,
            "No reviewed backend, attestation, deletion policy, or network permission is configured",
        ),
    )

    fun requireAvailable(id: String): OptionalFeature {
        val feature = features.firstOrNull { it.id == id } ?: error("Unknown feature: $id")
        check(feature.availability == FeatureAvailability.AVAILABLE) { feature.reason ?: "$id is unavailable" }
        return feature
    }
}
