package ch.lkmc.kirsch.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class Camera2BurstController(
    private val context: Context,
    private val textureView: TextureView,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(message: String)
        fun onBusyChanged(busy: Boolean)
        fun onCaptureFinished(manifestPath: String)
    }

    private data class CameraConfig(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val mode: CaptureMode,
        val previewSize: Size,
        val captureSize: Size,
        val imageFormat: Int,
        val minFrameDurationNs: Long,
        val maxFrameDurationNs: Long,
        val stallDurationNs: Long,
        val capabilities: Set<Int>,
    ) {
        val supportsManualSensor: Boolean =
            capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val supportsManualPostProcessing: Boolean =
            capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)
        val supportsBurst: Boolean =
            capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE)
        val adjustableFocus: Boolean =
            (characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f
        private val afModes: Set<Int> = characteristics.get(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        )?.toSet().orEmpty()
        val autoFocusLockAvailable: Boolean =
            adjustableFocus && afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)
        val continuousFocusAvailable: Boolean =
            adjustableFocus && afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val aeLockAvailable: Boolean =
            characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbLockAvailable: Boolean =
            characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
    }

    private data class LockPlan(
        val generation: Long,
        val printId: String,
        val captureId: String,
        val convergenceDeadlineElapsedNs: Long,
        val warnings: MutableList<String> = mutableListOf(),
        var lockStarted: Boolean = false,
        var lockDeadlineElapsedNs: Long = 0,
    )

    private data class PreviewTag(
        val generation: Long,
        val locking: Boolean,
        val captureId: String? = null,
    )

    private companion object {
        const val LOCK_TIMEOUT_MS = 2_000L
        const val BURST_TIMEOUT_MS = 30_000L
        const val MAX_YUV_PIXELS = 12_000_000L
        const val MAX_RAW_PIXELS = 16_000_000L
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("kirsch-camera").apply { start() }
    private val imageThread = HandlerThread("kirsch-images").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val cameraExecutor = Executor { command -> cameraHandler.post(command) }
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private var generation = 0L
    private var requestedMode = CaptureMode.RAW
    private var requestedProfile = CaptureProfile.QUALITY_RAW
    private var config: CameraConfig? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private val retiredReaders = mutableListOf<ImageReader>()
    private var previewSurface: Surface? = null
    private var latestPreviewResult: TotalCaptureResult? = null
    private var lockPlan: LockPlan? = null
    private var activePairer: TimestampPairer<Image, TaggedCaptureResult>? = null
    private var activeWriter: CapturePackageWriter? = null
    private var activeWriterGeneration = -1L
    private var activeWrites = 0
    private var finishRequested = false
    private var finishSuccess = true
    private var closed = false
    private var shutdownPending = false

    fun start(mode: CaptureMode) {
        start(if (mode == CaptureMode.RAW) CaptureProfile.QUALITY_RAW else CaptureProfile.QUALITY_YUV)
    }

    fun start(profile: CaptureProfile) {
        cameraHandler.post {
            if (closed) return@post
            requestedProfile = profile
            requestedMode = profile.preferredMode
            generation += 1
            closeCameraResources(markActiveCaptureFailed = true)
            openCamera(generation)
        }
    }

    fun capture(printId: String) {
        cameraHandler.post {
            if (activeWriter != null || lockPlan != null) {
                status("A burst is already in progress")
                return@post
            }
            val session = captureSession
            val device = cameraDevice
            val currentConfig = config
            if (session == null || device == null || currentConfig == null) {
                status("Camera is not ready")
                return@post
            }
            val normalizedPrintId = CaptureIdentity.normalizePrintId(printId)
            val captureId = CaptureIdentity.captureId(
                Instant.now(),
                UUID.randomUUID().toString().take(8),
            )
            val writer = try {
                CapturePackageWriter(
                    context,
                    captureId,
                    normalizedPrintId,
                    currentConfig.mode,
                    requestedProfile.frameCount,
                    currentConfig.characteristics,
                    cameraJson(currentConfig),
                    requestedProfile,
                )
            } catch (error: Exception) {
                status("Unable to create capture package: ${error.message}")
                return@post
            }
            activeWriter = writer
            activeWriterGeneration = generation
            activeWrites = 0
            finishRequested = false
            finishSuccess = true
            if (requestedMode != currentConfig.mode) {
                writer.addWarning(
                    "Requested ${requestedMode.manifestValue}; fell back to ${currentConfig.mode.manifestValue}",
                )
            }
            if (requestedProfile.frameIntervalNs > 0 && !currentConfig.supportsManualSensor) {
                writer.addWarning(
                    "Requested sweep pacing is advisory because this camera cannot set SENSOR_FRAME_DURATION; actual timestamps are authoritative",
                )
            }
            lockPlan = LockPlan(
                generation = generation,
                printId = normalizedPrintId,
                captureId = captureId,
                convergenceDeadlineElapsedNs =
                    SystemClock.elapsedRealtimeNanos() + LOCK_TIMEOUT_MS * 1_000_000,
            )
            listener.onBusyChanged(true)
            status("Waiting for preview 3A convergence")
            latestPreviewResult?.let(::maybeBeginThreeALock)
            cameraHandler.postDelayed({
                val plan = lockPlan
                if (plan != null && plan.captureId == captureId && !plan.lockStarted) {
                    plan.warnings += "Preview 3A convergence timed out before lock"
                    beginThreeALock(plan, session, device, currentConfig)
                }
            }, LOCK_TIMEOUT_MS)
        }
    }

    fun stop() {
        cameraHandler.post {
            generation += 1
            closeCameraResources(markActiveCaptureFailed = true)
        }
    }

    fun shutdown() {
        cameraHandler.post {
            if (closed) return@post
            closed = true
            shutdownPending = true
            generation += 1
            closeCameraResources(markActiveCaptureFailed = true)
            if (activeWriter == null && activeWrites == 0) completeShutdown()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(openGeneration: Long) {
        if (!textureView.isAvailable) {
            status("Preview surface is not available")
            return
        }
        try {
            val selected = selectCamera(requestedMode)
            config = selected
            status(describeConfig(selected))
            val reader = ImageReader.newInstance(
                selected.captureSize.width,
                selected.captureSize.height,
                selected.imageFormat,
                requestedProfile.frameCount + 2,
            )
            reader.setOnImageAvailableListener({ source -> drainImages(source, openGeneration) }, imageHandler)
            imageReader = reader
            cameraManager.openCamera(
                selected.cameraId,
                cameraExecutor,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (openGeneration != generation || closed) {
                            camera.close()
                            return
                        }
                        cameraDevice = camera
                        createSession(camera, selected, openGeneration)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        status("Camera disconnected")
                        if (openGeneration == generation && cameraDevice === camera) {
                            closeCameraResources(markActiveCaptureFailed = true)
                        } else {
                            camera.close()
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        status("Camera open error: $error")
                        if (openGeneration == generation && cameraDevice === camera) {
                            closeCameraResources(markActiveCaptureFailed = true)
                        } else {
                            camera.close()
                        }
                    }
                },
            )
        } catch (error: Exception) {
            status("Unable to open camera: ${error.message}")
            closeCameraResources(markActiveCaptureFailed = true)
        }
    }

    private fun selectCamera(mode: CaptureMode): CameraConfig {
        val rearCameras = cameraManager.cameraIdList.mapNotNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId to characteristics
            } else {
                null
            }
        }
        check(rearCameras.isNotEmpty()) { "No rear camera found" }
        val rawCamera = rearCameras.firstOrNull { (_, characteristics) ->
            val capabilities = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            ) ?: intArrayOf()
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                !map?.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
        }
        val (cameraId, characteristics) =
            if (mode == CaptureMode.RAW && rawCamera != null) rawCamera else rearCameras.first()
        val actualMode = if (mode == CaptureMode.RAW && rawCamera != null) CaptureMode.RAW else CaptureMode.YUV
        if (mode == CaptureMode.RAW && actualMode == CaptureMode.YUV) {
            status("RAW is unavailable on rear cameras; using YUV fallback")
        }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("Camera has no stream configuration map")
        val format = if (actualMode == CaptureMode.RAW) ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
        val captureSizes = map.getOutputSizes(format)?.toList().orEmpty()
        check(captureSizes.isNotEmpty()) { "No ${actualMode.displayName} output sizes" }
        val captureSize = chooseCaptureSize(captureSizes, actualMode)
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        check(previewSizes.isNotEmpty()) { "No preview output sizes" }
        val previewSize = choosePreviewSize(previewSizes, captureSize)
        val capabilities = characteristics.get(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
        )?.toSet().orEmpty()
        return CameraConfig(
            cameraId = cameraId,
            characteristics = characteristics,
            mode = actualMode,
            previewSize = previewSize,
            captureSize = captureSize,
            imageFormat = format,
            minFrameDurationNs = map.getOutputMinFrameDuration(format, captureSize),
            maxFrameDurationNs = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
                ?: Long.MAX_VALUE,
            stallDurationNs = map.getOutputStallDuration(format, captureSize),
            capabilities = capabilities,
        )
    }

    private fun chooseCaptureSize(sizes: List<Size>, mode: CaptureMode): Size {
        val ranked = sizes.sortedByDescending(::area)
        val pixelLimit = if (mode == CaptureMode.RAW) MAX_RAW_PIXELS else MAX_YUV_PIXELS
        return ranked.firstOrNull { area(it) <= pixelLimit } ?: ranked.last()
    }

    private fun choosePreviewSize(sizes: List<Size>, captureSize: Size): Size {
        val targetRatio = captureSize.width.toDouble() / captureSize.height
        return sizes
            .filter { area(it) <= 1920L * 1080L }
            .minWithOrNull(
                compareBy<Size> { abs(it.width.toDouble() / it.height - targetRatio) }
                    .thenByDescending(::area),
            ) ?: sizes.minBy { abs(area(it) - 1920L * 1080L) }
    }

    private fun area(size: Size): Long = size.width.toLong() * size.height

    private fun createSession(camera: CameraDevice, selected: CameraConfig, openGeneration: Long) {
        val texture = textureView.surfaceTexture ?: error("TextureView lost its SurfaceTexture")
        texture.setDefaultBufferSize(selected.previewSize.width, selected.previewSize.height)
        val surface = Surface(texture)
        previewSurface = surface
        val readerSurface = imageReader?.surface ?: error("ImageReader is unavailable")
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface), OutputConfiguration(readerSurface)),
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (openGeneration != generation || cameraDevice == null) {
                        session.close()
                        return
                    }
                    captureSession = session
                    startAutomaticPreview(session, camera, selected, openGeneration)
                    status("Camera ready")
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    status("Camera rejected preview + ${selected.mode.displayName} stream combination")
                    session.close()
                }
            },
        )
        camera.createCaptureSession(sessionConfig)
    }

    private fun startAutomaticPreview(
        session: CameraCaptureSession,
        camera: CameraDevice,
        selected: CameraConfig,
        previewGeneration: Long,
    ) {
        val surface = previewSurface ?: return
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(
                CaptureRequest.CONTROL_AF_MODE,
                if (selected.continuousFocusAvailable) {
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                } else if (selected.autoFocusLockAvailable) {
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                } else {
                    CaptureRequest.CONTROL_AF_MODE_OFF
                },
            )
            set(CaptureRequest.CONTROL_AE_LOCK, false)
            set(CaptureRequest.CONTROL_AWB_LOCK, false)
            setTag(PreviewTag(previewGeneration, locking = false))
        }.build()
        session.setSingleRepeatingRequest(request, cameraExecutor, previewCallback)
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val tag = request.tag
            if (tag is PreviewTag && tag.generation == generation && !tag.locking) {
                latestPreviewResult = result
                maybeBeginThreeALock(result)
            }
        }
    }

    private fun maybeBeginThreeALock(result: TotalCaptureResult) {
        val plan = lockPlan ?: return
        if (plan.generation != generation || plan.lockStarted) return
        val converged = previewThreeAConverged(result, config ?: return)
        if (!converged) {
            if (SystemClock.elapsedRealtimeNanos() < plan.convergenceDeadlineElapsedNs) return
            plan.warnings += "Preview 3A convergence timed out before lock"
        }
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val selected = config ?: return
        beginThreeALock(plan, session, camera, selected)
    }

    private fun previewThreeAConverged(
        result: TotalCaptureResult,
        selected: CameraConfig,
    ): Boolean = ThreeAStatePolicy.previewConverged(
        result.get(CaptureResult.CONTROL_AE_STATE),
        result.get(CaptureResult.CONTROL_AWB_STATE),
        result.get(CaptureResult.CONTROL_AF_STATE),
        selected.continuousFocusAvailable,
    )

    private fun beginThreeALock(
        plan: LockPlan,
        session: CameraCaptureSession,
        camera: CameraDevice,
        selected: CameraConfig,
    ) {
        if (plan.lockStarted || lockPlan?.captureId != plan.captureId) return
        plan.lockStarted = true
        plan.lockDeadlineElapsedNs = SystemClock.elapsedRealtimeNanos() + LOCK_TIMEOUT_MS * 1_000_000
        status("Locking exposure, white balance, and focus")
        try {
            val trigger = buildLockRequest(
                camera,
                selected,
                CaptureRequest.CONTROL_AF_TRIGGER_START,
                plan,
            )
            session.captureSingleRequest(
                trigger,
                cameraExecutor,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        callbackSession: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        if (!isCurrentPlan(plan) || callbackSession !== captureSession) return
                        latestPreviewResult = result
                        try {
                            val idle = buildLockRequest(
                                camera,
                                selected,
                                CaptureRequest.CONTROL_AF_TRIGGER_IDLE,
                                plan,
                            )
                            callbackSession.setSingleRepeatingRequest(idle, cameraExecutor, lockCallback)
                        } catch (error: Exception) {
                            failBeforeBurst("Unable to hold 3A lock: ${error.message}")
                        }
                    }

                    override fun onCaptureFailed(
                        callbackSession: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        if (!isCurrentPlan(plan) || callbackSession !== captureSession) return
                        plan.warnings += "3A trigger failed: reason=${failure.reason}"
                        latestPreviewResult?.let { submitBurst(it) } ?: failBeforeBurst("No preview result")
                    }
                },
            )
            cameraHandler.postDelayed({
                if (isCurrentPlan(plan) && plan.lockStarted) {
                    plan.warnings += "3A lock timed out; actual per-frame states are authoritative"
                    latestPreviewResult?.let(::submitBurst) ?: failBeforeBurst("No result after 3A timeout")
                }
            }, LOCK_TIMEOUT_MS)
        } catch (error: Exception) {
            failBeforeBurst("Unable to start 3A lock: ${error.message}")
        }
    }

    private fun buildLockRequest(
        camera: CameraDevice,
        selected: CameraConfig,
        afTrigger: Int,
        plan: LockPlan,
    ): CaptureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(previewSurface ?: error("Preview surface missing"))
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        set(CaptureRequest.CONTROL_AE_LOCK, selected.aeLockAvailable)
        set(CaptureRequest.CONTROL_AWB_LOCK, selected.awbLockAvailable)
        if (selected.autoFocusLockAvailable) {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
        setTag(PreviewTag(plan.generation, locking = true, captureId = plan.captureId))
    }.build()

    private val lockCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val plan = lockPlan ?: return
            if (plan.generation != generation) return
            val tag = request.tag as? PreviewTag ?: return
            if (tag.captureId != plan.captureId) return
            latestPreviewResult = result
            val selected = config ?: return
            val timedOut = SystemClock.elapsedRealtimeNanos() >= plan.lockDeadlineElapsedNs
            if (threeAReady(result, selected) || timedOut) {
                if (timedOut) plan.warnings += "3A lock timed out; actual per-frame states are authoritative"
                submitBurst(result)
            }
        }
    }

    private fun isCurrentPlan(plan: LockPlan): Boolean =
        plan.generation == generation &&
            lockPlan?.captureId == plan.captureId &&
            activeWriter?.captureId == plan.captureId

    private fun threeAReady(result: TotalCaptureResult, selected: CameraConfig): Boolean {
        val manualSensorWillBeUsed = selected.supportsManualSensor &&
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME) != null &&
            result.get(CaptureResult.SENSOR_SENSITIVITY) != null
        return ThreeAStatePolicy.locked(
            result.get(CaptureResult.CONTROL_AE_STATE),
            result.get(CaptureResult.CONTROL_AWB_STATE),
            result.get(CaptureResult.CONTROL_AF_STATE),
            ThreeAStatePolicy.aeLockRequired(
                selected.aeLockAvailable,
                manualSensorWillBeUsed,
            ),
            selected.awbLockAvailable,
            selected.autoFocusLockAvailable,
        )
    }

    private fun submitBurst(lockedResult: TotalCaptureResult) {
        val plan = lockPlan ?: return
        val selected = config ?: return failBeforeBurst("Camera configuration was lost before burst")
        val session = captureSession ?: return failBeforeBurst("Capture session was lost before burst")
        val camera = cameraDevice ?: return failBeforeBurst("Camera disconnected before burst")
        val reader = imageReader ?: return failBeforeBurst("Image reader was lost before burst")
        lockPlan = null
        val writer = activeWriter
        if (writer == null || writer.captureId != plan.captureId) {
            failBeforeBurst("Capture package is no longer active")
            return
        }
        plan.warnings.forEach(writer::addWarning)
        activePairer = TimestampPairer(
            maxPendingImages = requestedProfile.frameCount + 1,
            onPair = { image, result -> dispatchFrameWrite(writer, image, result) },
            onDropImage = Image::close,
        )
        try {
            session.stopRepeating()
            val requests = (0 until requestedProfile.frameCount).map { frameIndex ->
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applyLockedValues(this, selected, lockedResult)
                    setTag(BurstFrameTag(plan.generation, plan.captureId, frameIndex))
                }.build()
            }
            status("Capturing ${requestedProfile.displayName} (${requestedProfile.frameCount} frames)")
            session.captureBurstRequests(requests, cameraExecutor, createBurstCallback(plan, writer))
            cameraHandler.postDelayed({
                if (activeWriter === writer) {
                    val pending = activePairer?.pendingCounts()
                    writer.addError("Burst timeout; pending images/results=$pending")
                    requestFinish(success = false)
                }
            }, BURST_TIMEOUT_MS)
        } catch (error: Exception) {
            writer.addError("Burst submission failed: ${error.message}")
            requestFinish(success = false)
        }
    }

    private fun applyLockedValues(
        builder: CaptureRequest.Builder,
        selected: CameraConfig,
        locked: TotalCaptureResult,
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        val exposure = locked.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val sensitivity = locked.get(CaptureResult.SENSOR_SENSITIVITY)
        val frameDuration = locked.get(CaptureResult.SENSOR_FRAME_DURATION)
        if (selected.supportsManualSensor && exposure != null && sensitivity != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
            builder.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                maxOf(
                    frameDuration ?: 0L,
                    exposure,
                    selected.minFrameDurationNs,
                    requestedProfile.frameIntervalNs,
                ).coerceAtMost(selected.maxFrameDurationNs),
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, selected.aeLockAvailable)
        }
        val focusDistance = locked.get(CaptureResult.LENS_FOCUS_DISTANCE)
        if (selected.adjustableFocus && focusDistance != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
        val gains = locked.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val transform = locked.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val gainsPlausible = gains != null && transform != null &&
            WhiteBalanceStrategy.gainsPlausible(
                gains.red,
                gains.greenEven,
                gains.greenOdd,
                gains.blue,
            )
        when (
            WhiteBalanceStrategy.select(
                selected.awbLockAvailable,
                selected.supportsManualPostProcessing,
                gainsPlausible,
            )
        ) {
            WhiteBalanceStrategy.Mode.LOCK -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            WhiteBalanceStrategy.Mode.MANUAL_REPLAY -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
            }
            WhiteBalanceStrategy.Mode.AUTO -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, selected.awbLockAvailable)
            }
        }
        val shadingModes = selected.characteristics.get(
            CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES,
        ) ?: intArrayOf()
        if (shadingModes.contains(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            )
        }
    }

    private fun createBurstCallback(
        plan: LockPlan,
        writer: CapturePackageWriter,
    ) = object : CameraCaptureSession.CaptureCallback() {
        private fun isCurrent(session: CameraCaptureSession): Boolean =
            plan.generation == generation &&
                activeWriter === writer &&
                captureSession === session

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (!isCurrent(session)) return
            val tag = request.tag as? BurstFrameTag ?: return
            if (tag.captureId != writer.captureId) return
            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
            if (timestamp == null) {
                writer.addError("Frame ${tag.frameIndex} has no SENSOR_TIMESTAMP")
                requestFinish(success = false)
                return
            }
            activePairer?.addResult(timestamp, TaggedCaptureResult(tag, result))
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            if (!isCurrent(session)) return
            val tag = request.tag as? BurstFrameTag ?: return
            if (tag.captureId != writer.captureId) return
            writer.addError("Frame ${tag.frameIndex} failed: reason=${failure.reason}")
            requestFinish(success = false)
        }

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long,
        ) {
            if (!isCurrent(session)) return
            val tag = request.tag as? BurstFrameTag ?: return
            if (tag.captureId != writer.captureId) return
            writer.addError("Frame ${tag.frameIndex} buffer lost at $frameNumber")
            requestFinish(success = false)
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (!isCurrent(session)) return
            writer.addError("Burst sequence aborted: $sequenceId")
            requestFinish(success = false)
        }
    }

    private fun drainImages(reader: ImageReader, imageGeneration: Long) {
        while (true) {
            val image = try {
                reader.acquireNextImage()
            } catch (_: IllegalStateException) {
                null
            } ?: break
            cameraHandler.post {
                if (imageGeneration != generation || activePairer == null) {
                    image.close()
                } else {
                    activePairer?.addImage(image.timestamp, image)
                }
            }
        }
    }

    private fun dispatchFrameWrite(
        writer: CapturePackageWriter,
        image: Image,
        result: TaggedCaptureResult,
    ) {
        writer.recordDeliveredFrame()
        activeWrites += 1
        ioExecutor.execute {
            var success = true
            var count = 0
            try {
                count = writer.writeFrame(image, result)
            } catch (error: Exception) {
                success = false
                writer.addError("Frame ${result.tag.frameIndex} write failed: ${error.message}")
            } finally {
                image.close()
            }
            cameraExecutor.execute {
                if (activeWriter !== writer) return@execute
                activeWrites -= 1
                if (!success) finishSuccess = false
                if (activeWrites == 0) closeRetiredReaders()
                if (count == requestedProfile.frameCount) requestFinish(success = finishSuccess)
                else if (finishRequested && activeWrites == 0) finalizeActiveWriter()
            }
        }
    }

    private fun requestFinish(success: Boolean) {
        finishRequested = true
        finishSuccess = finishSuccess && success
        activePairer?.clear()
        activePairer = null
        if (activeWrites == 0) finalizeActiveWriter()
    }

    private fun finalizeActiveWriter() {
        val writer = activeWriter ?: return
        val shouldRestorePreview = activeWriterGeneration == generation
        val manifest = try {
            writer.finish(finishSuccess)
        } catch (error: Exception) {
            status("Unable to finalize capture: ${error.message}")
            null
        }
        activeWriter = null
        activeWriterGeneration = -1L
        finishRequested = false
        finishSuccess = true
        listener.onBusyChanged(false)
        if (manifest != null) {
            status("Capture package: ${writer.outputDirectory()}")
            listener.onCaptureFinished(manifest.absolutePath)
        }
        if (shutdownPending) completeShutdown()
        else if (shouldRestorePreview) restoreAutomaticPreview()
    }

    private fun restoreAutomaticPreview() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val selected = config ?: return
        val restoreGeneration = generation
        try {
            val cancel = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface ?: return)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                set(CaptureRequest.CONTROL_AWB_LOCK, false)
            }.build()
            session.captureSingleRequest(
                cancel,
                cameraExecutor,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        callbackSession: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        if (restoreGeneration != generation ||
                            callbackSession !== captureSession ||
                            camera !== cameraDevice
                        ) {
                            return
                        }
                        startAutomaticPreview(callbackSession, camera, selected, restoreGeneration)
                    }
                },
            )
        } catch (error: CameraAccessException) {
            status("Unable to restore preview: ${error.message}")
        }
    }

    private fun failBeforeBurst(message: String) {
        status(message)
        lockPlan = null
        activeWriter?.addError(message)
        requestFinish(success = false)
    }

    private fun closeCameraResources(markActiveCaptureFailed: Boolean) {
        lockPlan = null
        if (markActiveCaptureFailed && activeWriter != null) {
            activeWriter?.addError("Camera lifecycle ended before burst completion")
            activeWriterGeneration = -1L
            requestFinish(success = false)
        }
        activePairer?.clear()
        activePairer = null
        try {
            captureSession?.abortCaptures()
        } catch (_: CameraAccessException) {
            // Closing below is sufficient.
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.let { reader ->
            if (activeWrites > 0) retiredReaders += reader else reader.close()
        }
        imageReader = null
        previewSurface?.release()
        previewSurface = null
        latestPreviewResult = null
        config = null
        if (activeWriter == null) listener.onBusyChanged(false)
    }

    private fun completeShutdown() {
        closeRetiredReaders()
        ioExecutor.shutdown()
        imageThread.quitSafely()
        cameraThread.quitSafely()
    }

    private fun closeRetiredReaders() {
        retiredReaders.forEach(ImageReader::close)
        retiredReaders.clear()
    }

    private fun describeConfig(selected: CameraConfig): String = buildString {
        append("camera=${selected.cameraId} mode=${selected.mode.displayName} ")
        append("preview=${selected.previewSize.width}x${selected.previewSize.height} ")
        append("capture=${selected.captureSize.width}x${selected.captureSize.height}\n")
        append("manualSensor=${selected.supportsManualSensor} ")
        append("manualPost=${selected.supportsManualPostProcessing} ")
        append("burst=${selected.supportsBurst} ")
        append("stall=${selected.stallDurationNs}ns minFrame=${selected.minFrameDurationNs}ns ")
        append("maxFrame=${selected.maxFrameDurationNs}ns")
    }

    private fun cameraJson(selected: CameraConfig) = CaptureMetadata.characteristicsJson(
        selected.cameraId,
        selected.characteristics,
        selected.previewSize,
        selected.captureSize,
        selected.mode,
        selected.minFrameDurationNs,
        selected.stallDurationNs,
    ).put("requested_capture_mode", requestedMode.manifestValue)
        .put("capture_profile", requestedProfile.manifestValue)
        .put("requested_frame_count", requestedProfile.frameCount)
        .put("requested_frame_interval_ns", requestedProfile.frameIntervalNs)
        .put(
            "effective_frame_interval_ns",
            requestedProfile.frameIntervalNs.coerceAtMost(selected.maxFrameDurationNs),
        )
        .put("burst_capability", selected.supportsBurst)
        .put("manual_sensor_capability", selected.supportsManualSensor)
        .put("manual_post_processing_capability", selected.supportsManualPostProcessing)

    private fun status(message: String) {
        listener.onStatus(message)
    }
}
