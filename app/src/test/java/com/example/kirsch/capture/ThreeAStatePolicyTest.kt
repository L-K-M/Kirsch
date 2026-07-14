package com.example.kirsch.capture

import android.hardware.camera2.CaptureResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeAStatePolicyTest {
    @Test
    fun previewRequiresConvergedAeAwbAndFocus() {
        assertTrue(
            ThreeAStatePolicy.previewConverged(
                CaptureResult.CONTROL_AE_STATE_CONVERGED,
                CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                focusLockRequired = true,
            ),
        )
        assertFalse(
            ThreeAStatePolicy.previewConverged(
                CaptureResult.CONTROL_AE_STATE_SEARCHING,
                CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
                focusLockRequired = true,
            ),
        )
    }

    @Test
    fun fixedFocusDoesNotRequireAfState() {
        assertTrue(
            ThreeAStatePolicy.locked(
                CaptureResult.CONTROL_AE_STATE_LOCKED,
                CaptureResult.CONTROL_AWB_STATE_LOCKED,
                null,
                aeLockRequired = true,
                awbLockRequired = true,
                focusLockRequired = false,
            ),
        )
    }

    @Test
    fun triggeredAutoFocusDoesNotRequirePassivePreviewState() {
        assertTrue(
            ThreeAStatePolicy.previewConverged(
                CaptureResult.CONTROL_AE_STATE_CONVERGED,
                CaptureResult.CONTROL_AWB_STATE_CONVERGED,
                CaptureResult.CONTROL_AF_STATE_INACTIVE,
                focusLockRequired = false,
            ),
        )
    }

    @Test
    fun manualSensorPathDoesNotWaitForImpossibleAeLockedState() {
        assertFalse(
            ThreeAStatePolicy.aeLockRequired(
                aeLockAvailable = true,
                manualSensorWillBeUsed = true,
            ),
        )
        assertTrue(
            ThreeAStatePolicy.locked(
                CaptureResult.CONTROL_AE_STATE_INACTIVE,
                CaptureResult.CONTROL_AWB_STATE_LOCKED,
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                aeLockRequired = false,
                awbLockRequired = true,
                focusLockRequired = true,
            ),
        )
    }
}
