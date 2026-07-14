package ch.lkmc.kirsch.capture

import android.hardware.camera2.CaptureResult

object ThreeAStatePolicy {
    fun previewConverged(
        aeState: Int?,
        awbState: Int?,
        afState: Int?,
        focusLockRequired: Boolean,
    ): Boolean {
        val aeReady = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
        val awbReady = awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED
        val afReady = !focusLockRequired ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        return aeReady && awbReady && afReady
    }

    fun locked(
        aeState: Int?,
        awbState: Int?,
        afState: Int?,
        aeLockRequired: Boolean,
        awbLockRequired: Boolean,
        focusLockRequired: Boolean,
    ): Boolean {
        val aeReady = !aeLockRequired || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
        val awbReady = !awbLockRequired || awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED
        val afReady = !focusLockRequired ||
            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        return aeReady && awbReady && afReady
    }

    fun aeLockRequired(aeLockAvailable: Boolean, manualSensorWillBeUsed: Boolean): Boolean =
        aeLockAvailable && !manualSensorWillBeUsed
}
