package com.example.kirsch.derivative

enum class GenerativeKind { SUPER_RESOLUTION, FACE_RESTORATION, INPAINTING, COLORIZATION }

data class IdentityEvidence(
    val faceEmbeddingDistance: Double,
    val landmarkError: Double,
    val embeddingThreshold: Double,
    val landmarkThreshold: Double,
) {
    val passes: Boolean
        get() = faceEmbeddingDistance <= embeddingThreshold && landmarkError <= landmarkThreshold
}

object GenerativeGate {
    fun authorize(kind: GenerativeKind, touchesFace: Boolean, evidence: IdentityEvidence?): Boolean {
        FeatureCatalog.requireAvailable("generative-restoration")
        require(!touchesFace || evidence != null) { "$kind requires identity evidence when a face is affected" }
        require(evidence == null || evidence.passes) { "$kind failed identity-drift thresholds" }
        return true
    }
}
