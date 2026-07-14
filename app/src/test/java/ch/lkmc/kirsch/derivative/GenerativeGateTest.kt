package ch.lkmc.kirsch.derivative

import org.junit.Test

class GenerativeGateTest {
    @Test(expected = IllegalStateException::class)
    fun unavailableWeightsFailBeforeCreatingGenerativeOutput() {
        GenerativeGate.authorize(
            GenerativeKind.FACE_RESTORATION,
            touchesFace = true,
            evidence = IdentityEvidence(0.1, 0.4, 0.2, 0.5),
        )
    }
}
