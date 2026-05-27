package com.example.WymiAR.Activities

import android.content.Intent
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.sqrt
import android.util.Log
import android.widget.FrameLayout
import com.example.WymiAR.Objects.AppSettings
import com.example.WymiAR.Helpers.ModelPickerPopup
import com.example.WymiAR.Managers.ModelProfile
import com.example.WymiAR.Managers.ProfileManager
import com.example.WymiAR.Nodes.DefaultModelNode
import com.example.WymiAR.Nodes.PlaneGridRenderer
import com.example.WymiAR.Nodes.SelectedModelNode
import com.example.WymiAR.Helpers.WallMagnetMenuPopup
import com.example.WymiAR.Objects.PlaneMode
import com.example.WymiAR.Objects.DistanceUnit
import com.example.WymiAR.Overlays.MeasureTapeOverlayView
import com.example.WymiAR.Overlays.ModelControlOverlayView
import com.example.WymiAR.R
import com.example.WymiAR.Dialogs.ProfilePickerDialog
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.atan2
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import java.io.File
import java.util.UUID
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.widget.ImageView


fun log(msg: String) {
    Log.d("AR_DEBUG", msg)
}

class ARActivity : AppCompatActivity() {

    private lateinit var arSceneView: ARSceneView
    private lateinit var viewAttachmentManager: ViewAttachmentManager
    private lateinit var statusText: TextView
    private lateinit var modelControls: ModelControlOverlayView
    private lateinit var measureOverlay: MeasureTapeOverlayView
    private lateinit var modelPickerPopup: ModelPickerPopup
    private lateinit var wallMagnetMenuPopup: WallMagnetMenuPopup
    private lateinit var profileManager: ProfileManager

    private lateinit var settings: AppSettings
    private lateinit var measureModeButton: ImageButton
    private lateinit var wireframeModeButton: ImageButton
    private lateinit var animationToggleButton: ImageButton
    private lateinit var animationNextButton: ImageButton
    private lateinit var animationPauseButton: ImageButton
    private var currentAnimationIndex: Int = 0
    private lateinit var rotationRingToggleButton: ImageButton
    private lateinit var wallMagnetButton: ImageButton

    private var planeMode: PlaneMode = PlaneMode.HORIZONTAL

    private lateinit var btnLibrary : ImageButton
    private var unit: DistanceUnit = DistanceUnit.CENTIMETERS
    private lateinit var settingsButton: ImageButton
    private lateinit var backButton: ImageButton

    // ── Measure-mode action buttons ──────────────────────────────────────────
    private lateinit var btnMeasureDelete: ImageButton
    private lateinit var btnMeasureRevert: ImageButton

    private var isRotationRingVisible: Boolean = true
    private var isClosing: Boolean = false

    // ── Model colour override ─────────────────────────────────────────────────
    @androidx.annotation.ColorInt private var currentModelColour: Int? = null

    private lateinit var btnModelColour: FrameLayout
    private lateinit var modelColourBgRect: android.view.View
    private lateinit var modelColourPatchRect: android.view.View

    private lateinit var planeGridRenderer: PlaneGridRenderer

    // ── Dimension HUD views ──────────────────────────────────────────────────
    private lateinit var dimensionHud: View
    private lateinit var dimW: TextView
    private lateinit var dimH: TextView
    private lateinit var dimD: TextView

    private var isMeasureToolActive: Boolean = false
    private var isCameraModeActive: Boolean = false

    private lateinit var cameraModeButton: ImageButton
    private lateinit var btnCapturePhoto: ImageButton

    // ── Animation state ──────────────────────────────
    private var isAnimationPlaying: Boolean = false
    private var isAnimationStarted: Boolean = false


    private lateinit var planeScanOverlay: FrameLayout
    private var hasDetectedFirstPlane = false
    private var scanTimeoutRunnable: Runnable? = null

    // ── Measure state ────────────────────────────────────────────────────────

    private val measureLines = mutableListOf<Pair<AnchorNode, AnchorNode>>()

    private var pendingMeasureNode: AnchorNode? = null

    companion object {
        const val MAX_MEASURE_LINES = 5
    }

    // ── Other scene nodes ────────────────────────────────────────────────────
    private val placedMeasureNodes = mutableListOf<AnchorNode>()
    private val placedModelNodes   = mutableListOf<AnchorNode>()

    private val models         = mutableListOf<DefaultModelNode>()
    private var selectedModel: SelectedModelNode? = null

    private var isDragging   = false
    private var isPinching   = false
    private var isRotating   = false

    private var initialPinchDistance    = 0f
    private var touchStartPos           = PointF()
    private val MOVE_THRESHOLD          = 20f
    private var initialModelRotationY   = 0f

    private var activeModelPath: String    = "models/cat.glb"
    private lateinit var activeModelId: String
    private var activeModelIsAsset: Boolean = true
    private var activeModelSourceFormat: String? = null
    private val modelSourceFormats = mutableMapOf<String, String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        activeModelPath    = intent.getStringExtra(LibraryActivity.Companion.EXTRA_MODEL_PATH)    ?: "models/cat.glb"
        activeModelId    = intent.getStringExtra(LibraryActivity.Companion.EXTRA_MODEL_ID)
            ?: UUID.nameUUIDFromBytes(activeModelPath.toByteArray()).toString()
        activeModelIsAsset = intent.getBooleanExtra(LibraryActivity.Companion.EXTRA_MODEL_IS_ASSET, true)
        activeModelSourceFormat = intent.getStringExtra(LibraryActivity.Companion.EXTRA_SOURCE_FORMAT)

        profileManager = ProfileManager(this)
        settings = AppSettings(this)
        unit = settings.distanceUnit
        modelPickerPopup = ModelPickerPopup(this)
        wallMagnetMenuPopup = WallMagnetMenuPopup(this)

        arSceneView           = findViewById(R.id.arSceneView)
        statusText            = findViewById(R.id.statusText)
        modelControls         = findViewById(R.id.modelControls)
        measureOverlay        = findViewById(R.id.measureOverlay)
        backButton                = findViewById(R.id.btnBack)
        measureModeButton         = findViewById(R.id.btnMeasureTapeModeToggle)
        wireframeModeButton       = findViewById(R.id.btnWireframeToggle)
        animationToggleButton     = findViewById(R.id.btnAnimationToggle)
        animationNextButton       = findViewById(R.id.btnAnimationNext)
        animationPauseButton       = findViewById(R.id.btnAnimationPause)
        rotationRingToggleButton  = findViewById(R.id.btnRotationRingToggle)
        wallMagnetButton          = findViewById(R.id.btnWallMagnet)
        settingsButton            = findViewById(R.id.btnSettings)
        btnLibrary                = findViewById<ImageButton>(R.id.btnLibrary)
        btnModelColour            = findViewById(R.id.btnModelColour)
        modelColourBgRect         = btnModelColour.findViewById(R.id.modelColourBgRect)
        modelColourPatchRect      = btnModelColour.findViewById(R.id.modelColourPatchRect)

        // ── Measure action buttons ───────────────────────────────────────────
        btnMeasureDelete     = findViewById(R.id.btnMeasureDelete)
        btnMeasureRevert     = findViewById(R.id.btnMeasureRevert)

        btnMeasureDelete.setOnClickListener { clearMeasurements() }
        btnMeasureRevert.setOnClickListener { revertLastMeasurePoint() }

        // ── Dimension HUD ────────────────────────────────────────────────────
        dimensionHud = findViewById(R.id.dimensionHud)
        dimW = dimensionHud.findViewById(R.id.dimensionW)
        dimH = dimensionHud.findViewById(R.id.dimensionH)
        dimD = dimensionHud.findViewById(R.id.dimensionD)


        planeScanOverlay = findViewById(R.id.planeScanOverlay)
        startScanAnimation()

        modelControls.applySettings(AppSettings(this))
        modelControls.onUnitChanged = { newUnit ->
            unit = newUnit
            settings.distanceUnit = newUnit
            measureOverlay.setUnit(newUnit)
            if (dimensionHud.visibility == View.VISIBLE) {
                selectedModel?.getDimensionOverlay()?.let { updateDimensionHud(it.getDimensions()) }
            }
        }

        backButton.setOnClickListener { closeScene() }
        cameraModeButton  = findViewById(R.id.btnCameraMode)
        btnCapturePhoto   = findViewById(R.id.btnCapturePhoto)

        cameraModeButton.alpha = 0.5f
        cameraModeButton.setOnClickListener { toggleCameraMode() }
        btnCapturePhoto.setOnClickListener { capturePhoto() }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnLibrary.setOnClickListener { showModelPicker() }
        btnModelColour.setOnClickListener { showModelColourPicker() }
        viewAttachmentManager = ViewAttachmentManager(this, arSceneView)
        arSceneView.lifecycle = lifecycle

        arSceneView.configureSession { session, config ->
            config.planeFindingMode    = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.focusMode           = Config.FocusMode.AUTO
            config.updateMode          = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                config.depthMode = Config.DepthMode.AUTOMATIC
            }
        }

        // ── Custom plane renderer ─────────────────────────────────────────────
        planeGridRenderer = PlaneGridRenderer(arSceneView)
        planeGridRenderer.init()
        arSceneView.onSessionUpdated = { session, frame ->
            if (!isClosing) {
                if (arSceneView.session != null) {
                    planeGridRenderer.update(planeMode)
                }
                // ← ADD THIS
                if (!hasDetectedFirstPlane) {
                    val hasPlane = frame.getUpdatedTrackables(Plane::class.java)
                        .any { it.trackingState == TrackingState.TRACKING }
                    if (hasPlane) hideScanOverlay()
                }
            }
        }

        lifecycleScope.launch {
            val env = arSceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")
            arSceneView.indirectLight = env?.indirectLight?.apply { intensity = 40_000F }
            arSceneView.skybox        = null
            arSceneView.lightEstimator = null
        }

        modelControls.onSaveRequested = { showProfileDialog() }
        modelControls.onDeleteRequested = { deleteSelectedModel() }
        modelControls.onRelativeValuesChanged = { sx, sy, sz, rx, ry, rz ->
            selectedModel?.getWrappedNode()?.apply {
                currentRelativeScale = Float3(sx, sy, sz)
                currentRelativeRotation = Float3(rx, ry, rz)
            }
            if (dimensionHud.visibility == View.VISIBLE) {
                selectedModel?.getDimensionOverlay()?.let { updateDimensionHud(it.getDimensions()) }
            }

        }
        modelControls.visibility            = View.GONE
        wireframeModeButton.visibility      = View.GONE
        isAnimationPlaying = false
        isAnimationStarted = false
        refreshAnimationUI()
        rotationRingToggleButton.visibility = View.GONE

        measureOverlay.attach(arSceneView)
        measureOverlay.setUnit(unit)

        measureModeButton.alpha = 0.5f
        measureModeButton.setOnClickListener { toggleMeasureTool() }

        // ── Wall-magnet ───────────────────────────────────────────
        wallMagnetButton.setOnClickListener {
            if (wallMagnetMenuPopup.isShowing()) {
                wallMagnetMenuPopup.dismiss()
            } else {
                wallMagnetMenuPopup.onModeSelected = { mode -> setPlaneMode(mode) }
                wallMagnetMenuPopup.show(wallMagnetButton, planeMode)
            }
        }

        // ── Animation ────────────────────────────────────────────
        animationToggleButton.setOnClickListener {
            val wrapped = selectedModel ?: return@setOnClickListener
            if (isAnimationStarted) {
                wrapped.stopAllAnimations()
                isAnimationStarted = false
                isAnimationPlaying = false
                currentAnimationIndex = 0
            } else {
                isAnimationStarted = true
                isAnimationPlaying = true
                wrapped.resumeAnimation(currentAnimationIndex)
            }
            refreshAnimationUI()
            statusText.text = if (isAnimationStarted) getString(R.string.animation_toggle_on)
            else getString(R.string.animation_toggle_off)
        }

        animationPauseButton.setOnClickListener {
            val wrapped = selectedModel ?: return@setOnClickListener
            if (isAnimationPlaying) {
                wrapped.pauseAnimation()
                isAnimationPlaying = false
            } else {
                wrapped.resumeAnimation(currentAnimationIndex)
                isAnimationPlaying = true
            }
            refreshAnimationUI()
            statusText.text = if (isAnimationPlaying) getString(R.string.animation_toggle_on)
            else getString(R.string.animation_toggle_off)
        }

        animationNextButton.setOnClickListener {
            val wrapped = selectedModel ?: return@setOnClickListener
            val count = wrapped.getAnimationCount()
            if (count < 2) return@setOnClickListener
            currentAnimationIndex = (currentAnimationIndex + 1) % count
            wrapped.playAnimation(currentAnimationIndex)
            statusText.text = getString(R.string.status_animation_index, currentAnimationIndex + 1, count)
        }

        // ── Rotation ring────────────────────────────────────────
        rotationRingToggleButton.setOnClickListener {
            val wrapped = selectedModel ?: return@setOnClickListener
            isRotationRingVisible = !isRotationRingVisible
            if (isRotationRingVisible) {
                wrapped.showRotationHandle(arSceneView.engine, arSceneView)
                rotationRingToggleButton.alpha = 1.0f
                statusText.text = getString(R.string.rotation_ring_on)
            } else {
                wrapped.hideRotationHandle()
                rotationRingToggleButton.alpha = 0.5f
                statusText.text = getString(R.string.rotation_ring_off)
            }
        }

        statusText.text = getString(R.string.status_active_model, activeModelPath.substringAfterLast('/').substringBeforeLast('.'))

        arSceneView.onTouchEvent = onTouchEvent@{ motionEvent, hitResult ->
            val x = motionEvent.x
            val y = motionEvent.y

            val nodeHit = hitResult?.node

            val allowedPlaneTypes: Set<Plane.Type> = when (planeMode) {
                PlaneMode.VERTICAL   -> setOf(Plane.Type.VERTICAL)
                PlaneMode.HORIZONTAL -> setOf(
                    Plane.Type.HORIZONTAL_UPWARD_FACING,
                    Plane.Type.HORIZONTAL_DOWNWARD_FACING
                )
                PlaneMode.BOTH, PlaneMode.OFF -> setOf(
                    Plane.Type.HORIZONTAL_UPWARD_FACING,
                    Plane.Type.HORIZONTAL_DOWNWARD_FACING,
                    Plane.Type.VERTICAL
                )
            }

            val arHit: HitResult? = arSceneView.frame?.hitTest(x, y)
                ?.firstOrNull { hit ->
                    val plane = hit.trackable as? Plane ?: return@firstOrNull false
                    if (plane.trackingState != TrackingState.TRACKING) return@firstOrNull false
                    plane.type in allowedPlaneTypes && plane.isPoseInPolygon(hit.hitPose)
                }
            val selected = selectedModel

            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartPos.set(x, y)

                    if (nodeHit?.name == "rotation_handle") {
                        isRotating = true
                        touchStartPos.set(x, y)
                        initialModelRotationY = selected!!.rotation.y
                        isDragging = false
                    } else {
                        isRotating = false
                        isDragging = (selected != null && (nodeHit == selected || nodeHit?.parent == selected || nodeHit == selected.getWrappedNode()))
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (motionEvent.pointerCount == 2 && selected != null) {
                        isPinching = true
                        isDragging = false
                        initialPinchDistance = getFingerSpacing(motionEvent)
                        selected.startPinching()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    isPinching = false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isRotating && selected != null) {
                        val dx = motionEvent.x - touchStartPos.x
                        val sensitivity = 0.2f
                        val rotationOffset = dx * sensitivity
                        val newRotationY = initialModelRotationY + rotationOffset
                        selected.rotation = Float3(0f, newRotationY, 0f)
                        modelControls.updateRotationFromHandle(newRotationY)
                        if (dimensionHud.visibility == View.VISIBLE) {
                            selected.getDimensionOverlay()?.refresh()
                            selected.getDimensionOverlay()?.let { updateDimensionHud(it.getDimensions()) }
                        }
                        return@onTouchEvent true
                    }

                    if (isPinching && motionEvent.pointerCount >= 2 && selected != null) {
                        val currentDist = getFingerSpacing(motionEvent)
                        if (currentDist > 10f) {
                            val scaleFactor = currentDist / initialPinchDistance
                            selected.applyPinchScale(scaleFactor)
                            modelControls.updateScaleFromGesture(scaleFactor)
                            if (dimensionHud.visibility == View.VISIBLE) {
                                selected.getDimensionOverlay()
                                    ?.let { updateDimensionHud(it.getDimensions()) }
                            }
                        }
                        return@onTouchEvent true
                    }

                    if (isDragging && selected != null && arHit != null) {
                        selected.moveTo(Float3(arHit.hitPose.tx(), arHit.hitPose.ty(), arHit.hitPose.tz()))
                        return@onTouchEvent true
                    }

                    return@onTouchEvent false
                }

                MotionEvent.ACTION_UP -> {
                    val moved = Math.abs(x - touchStartPos.x) > MOVE_THRESHOLD ||
                            Math.abs(y - touchStartPos.y) > MOVE_THRESHOLD

                    isDragging  = false
                    isPinching  = false
                    isRotating  = false

                    if (moved) return@onTouchEvent false

                    if (isMeasureToolActive) {
                        val isReady = arSceneView.frame?.camera?.trackingState == TrackingState.TRACKING
                                && arSceneView.session?.getAllTrackables(Plane::class.java)
                            ?.any { it.trackingState == TrackingState.TRACKING } == true
                        if (!isReady) {
                            statusText.text = getString(R.string.status_move_camera_slow)
                            return@onTouchEvent true
                        }
                        if (arHit != null) placeMeasurePoint(arHit)
                        else statusText.text = getString(R.string.status_tap_on_surface)
                        return@onTouchEvent true
                    }

                    if (nodeHit != null) {
                        val clickedDefault = models.find { it == nodeHit || it == nodeHit.parent }
                        if (clickedDefault != null) {
                            selectModel(clickedDefault)
                            return@onTouchEvent true
                        }

                        if (selected != null &&
                            (nodeHit == selected || nodeHit.parent == selected ||
                                    nodeHit == selected.getWrappedNode())) {
                            return@onTouchEvent true
                        }

                        deselectModel()
                        return@onTouchEvent true
                    }

                    if (selected != null) {
                        deselectModel()
                        return@onTouchEvent true
                    }

                    if (arHit != null) {
                        placeModel(arHit)
                        return@onTouchEvent true
                    }

                    return@onTouchEvent false
                }
            }
            false
        }
    }

    private fun refreshAnimationUI() {
        val hasAnim   = selectedModel?.hasAnimations() == true
        val multiAnim = (selectedModel?.getAnimationCount() ?: 0) > 1
        animationToggleButton.visibility = if (hasAnim) View.VISIBLE else View.GONE
        animationToggleButton.alpha      = if (isAnimationStarted) 1.0f else 0.5f
        animationPauseButton.visibility  = if (hasAnim && isAnimationStarted) View.VISIBLE else View.GONE
        animationPauseButton.alpha       = if (isAnimationPlaying) 1.0f else 0.5f
        animationNextButton.visibility   = if (hasAnim && isAnimationStarted && multiAnim) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        isClosing = false
        viewAttachmentManager.onResume()
        modelControls.applySettings(settings)

        val newUnit = settings.distanceUnit
        if (newUnit != unit) {
            unit = newUnit
            measureOverlay.setUnit(unit)
            modelControls.updateUnit(unit)
        }
    }

    // ── Measure tool ──────────────────────────────────────────────────────────

    private fun toggleMeasureTool() {
        isMeasureToolActive = !isMeasureToolActive
        measureModeButton.alpha = if (isMeasureToolActive) 1.0f else 0.5f

        if (!isMeasureToolActive) {
            setMeasureNodesVisible(false)
            measureOverlay.visibility = View.INVISIBLE
            btnMeasureDelete.visibility = View.GONE
            btnMeasureRevert.visibility = View.GONE
            statusText.text = getString(
                R.string.status_active_model,
                activeModelPath.substringAfterLast('/').substringBeforeLast('.'))
        } else {
            deselectModel()
            setMeasureNodesVisible(true)
            measureOverlay.visibility = View.VISIBLE
            pushOverlayState()
            btnMeasureDelete.visibility = View.VISIBLE
            btnMeasureRevert.visibility = View.VISIBLE
            statusText.text = if (measureLines.isEmpty() && pendingMeasureNode == null)
                getString(R.string.measure_tap_first)
            else
                getString(R.string.measure_tap_second).takeIf { pendingMeasureNode != null }
                    ?: getString(R.string.status_measurements_restored)
        }
    }

    // ── Camera mode ───────────────────────────────────────────────────────────

    private fun toggleCameraMode() {
        isCameraModeActive = !isCameraModeActive
        cameraModeButton.alpha = if (isCameraModeActive) 1.0f else 0.5f

        if (isCameraModeActive) {
            if (isMeasureToolActive) toggleMeasureTool()
            if (selectedModel != null) deselectModel()
            setCameraModeUiVisible(false)
            btnCapturePhoto.visibility = View.VISIBLE
        } else {
            btnCapturePhoto.visibility = View.GONE
            setCameraModeUiVisible(true)
        }
    }

    private fun setCameraModeUiVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE

        btnLibrary.visibility     = vis
        btnModelColour.visibility = View.GONE

        val rootFrame = window.decorView.findViewById<android.widget.FrameLayout>(android.R.id.content)
            ?.getChildAt(0) as? android.widget.FrameLayout
        for (i in 0 until (rootFrame?.childCount ?: 0)) {
            if (rootFrame?.getChildAt(i) is android.widget.ScrollView) {
                rootFrame.getChildAt(i).visibility = vis
                break
            }
        }

        settingsButton.visibility = vis

        statusText.visibility   = vis
        dimensionHud.visibility = View.GONE
        modelControls.visibility = View.GONE

        btnMeasureDelete.visibility = View.GONE
        btnMeasureRevert.visibility = View.GONE

        if (!visible) {
            wireframeModeButton.visibility      = View.GONE
            rotationRingToggleButton.visibility = View.GONE
            animationToggleButton.visibility    = View.GONE
            animationPauseButton.visibility     = View.GONE
            animationNextButton.visibility      = View.GONE
            measureOverlay.visibility           = View.INVISIBLE
        }
    }

    private fun capturePhoto() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val bmp = android.graphics.Bitmap.createBitmap(
                arSceneView.width, arSceneView.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            android.view.PixelCopy.request(
                arSceneView,
                bmp,
                { result ->
                    if (result == android.view.PixelCopy.SUCCESS) {
                        savePhotoToGallery(bmp)
                    } else {
                        runOnUiThread {
                            statusText.visibility = View.VISIBLE
                            statusText.text = getString(R.string.camera_capture_failed)
                        }
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        }
    }

    private fun savePhotoToGallery(bmp: android.graphics.Bitmap) {
        val filename = "AR_${System.currentTimeMillis()}.jpg"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_PICTURES}/ARCaptures")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        )
        if (uri == null) {
            runOnUiThread {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.camera_capture_failed)
            }
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            runOnUiThread {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.camera_photo_saved)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isCameraModeActive) statusText.visibility = View.GONE
                }, 2000)
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            runOnUiThread {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.camera_capture_failed)
            }
        }
    }

    private fun setMeasureNodesVisible(visible: Boolean) {
        placedMeasureNodes.forEach { it.isVisible = visible }
    }

    private fun placeMeasurePoint(hitResult: HitResult) {
        val pending = pendingMeasureNode

        if (pending == null) {
            if (measureLines.size >= MAX_MEASURE_LINES) {
                statusText.text = getString(R.string.status_max_measurements, MAX_MEASURE_LINES)
                return
            }

            val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
            arSceneView.addChildNode(anchorNode)
            placedMeasureNodes.add(anchorNode)

            pendingMeasureNode = anchorNode
            pushOverlayState()
            statusText.text = getString(R.string.measure_tap_second)

        } else {
            val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
            arSceneView.addChildNode(anchorNode)
            placedMeasureNodes.add(anchorNode)

            measureLines.add(Pair(pending, anchorNode))
            pendingMeasureNode = null
            pushOverlayState()

            val a = pending.worldPosition.let { Float3(it.x, it.y, it.z) }
            val b = anchorNode.worldPosition.let { Float3(it.x, it.y, it.z) }
            val dist = distanceMeters(a, b)
            val (value, suffix) = unit.convert(dist)

            val linesLeft = MAX_MEASURE_LINES - measureLines.size
            statusText.text = if (linesLeft > 0)
                getString(R.string.measure_distance, value, suffix) + getString(R.string.measure_add_point)
            else
                getString(R.string.measure_distance, value, suffix) +  getString(R.string.measure_point_limit)
        }
    }

    private fun revertLastMeasurePoint() {
        val pending = pendingMeasureNode
        if (pending != null) {
            pending.anchor?.detach()
            pending.parent = null
            placedMeasureNodes.remove(pending)
            pendingMeasureNode = null
            pushOverlayState()

            val linesCount = measureLines.size
            statusText.text = if (linesCount == 0)
                getString(R.string.measure_tap_first)
            else
                getString(R.string.status_point_removed, linesCount)
        } else if (measureLines.isNotEmpty()) {
            val lastLine = measureLines.removeAt(measureLines.size - 1)
            val (nodeA, nodeB) = lastLine

            nodeB.anchor?.detach()
            nodeB.parent = null
            placedMeasureNodes.remove(nodeB)

            pendingMeasureNode = nodeA
            pushOverlayState()
            statusText.text = getString(R.string.measure_tap_second)
        } else {
            statusText.text = getString(R.string.measure_no_point)
        }
    }

    private fun clearMeasurements() {
        pendingMeasureNode?.let {
            it.anchor?.detach()
            it.parent = null
        }
        pendingMeasureNode = null
        measureLines.clear()

        placedMeasureNodes.forEach {
            it.anchor?.detach()
            it.parent = null
        }
        placedMeasureNodes.clear()

        measureOverlay.clear()

        if (isMeasureToolActive) {
            statusText.text = getString(R.string.measure_tap_first)
        }
    }

    private fun pushOverlayState() {
        measureOverlay.setMeasureData(measureLines.toList(), pendingMeasureNode)
    }

    // ── Wall-magnet mode ──────────────────────────────────────────────────────

    private fun setPlaneMode(mode: PlaneMode) {
        planeMode = mode

        arSceneView.configureSession { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        }
        planeGridRenderer.update(planeMode)

        when (mode) {
            PlaneMode.HORIZONTAL -> {
                wallMagnetButton.rotation = 0f
                wallMagnetButton.alpha    = 1.0f
                statusText.text = getString(R.string.status_floor_mode)
            }
            PlaneMode.VERTICAL -> {
                wallMagnetButton.rotation = -90f
                wallMagnetButton.alpha    = 1.0f
                statusText.text = getString(R.string.status_wall_mode)
            }
            PlaneMode.BOTH -> {
                wallMagnetButton.rotation = -45f
                wallMagnetButton.alpha    = 1.0f
                statusText.text = getString(R.string.status_all_surfaces)
            }
            PlaneMode.OFF -> {
                wallMagnetButton.rotation = 0f
                wallMagnetButton.alpha    = 0.35f
                statusText.text = getString(R.string.status_planes_hidden)
            }
        }
    }

    // ── Model placement ───────────────────────────────────────────────────────

    private fun placeModel(hitResult: HitResult) {
        statusText.text = getString(R.string.status_loading)

        lifecycleScope.launch {

            log("Loading path: $activeModelPath  isAsset: $activeModelIsAsset")

            val modelInstance = if (activeModelIsAsset) {
                arSceneView.modelLoader.createModelInstance(activeModelPath)
            } else {
                val file = File(activeModelPath)
                if (!file.exists()) {
                    log("ERROR: File not found at $activeModelPath")
                    statusText.text = getString(R.string.status_error_not_found)
                    return@launch
                }
                log("Loading imported model via ByteBuffer: $activeModelPath")
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                arSceneView.modelLoader.createModelInstance(
                    buffer = ByteBuffer.wrap(bytes)
                )
            }

            val node = DefaultModelNode(
                modelId               = activeModelId,
                modelPath             = activeModelPath,
                modelInstance         = modelInstance,
                scope                 = lifecycleScope,
                sceneView             = arSceneView
            )

            val hitPose = hitResult.hitPose
            val session = arSceneView.session ?: return@launch
            val uprightPose = Pose.makeTranslation(hitPose.tx(), hitPose.ty(), hitPose.tz())
            val anchor = session.createAnchor(uprightPose)

            val anchorNode = AnchorNode(arSceneView.engine, anchor)
            anchorNode.addChildNode(node)
            arSceneView.addChildNode(anchorNode)

            if (planeMode == PlaneMode.VERTICAL) {
                val qx = hitPose.qx(); val qy = hitPose.qy()
                val qz = hitPose.qz(); val qw = hitPose.qw()
                val normalX =  2f * (qx * qz + qy * qw)
                val normalZ =  1f - 2f * (qx * qx + qy * qy)
                val yawDeg = Math.toDegrees(atan2(normalX.toDouble(), normalZ.toDouble())).toFloat()
                node.rotation = Float3(0f, yawDeg, 0f)
                log("Wall placement: normal=($normalX, $normalZ) → yaw=$yawDeg°")
            }

            placedModelNodes.add(anchorNode)

            log("Adding new model to models list")
            models.add(node)
            modelSourceFormats[node.getModeleName()] = activeModelSourceFormat
            node.setAnimationPlaying(false)

            val profile = profileManager.loadDefault(node.getModeleName())
            if (profile != null) {
                node.scale = Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
            }

            val savedColour = profileManager.loadColorOverride(activeModelId)
            if (savedColour != null &&
                activeModelSourceFormat in setOf("stl", "obj", "ply", "3ds", "fbx")) {
                val rm = arSceneView.engine.renderableManager
                for (entity in node.modelInstance.asset.renderableEntities) {
                    val ri = rm.getInstance(entity)
                    if (ri == 0) continue
                    for (i in 0 until rm.getPrimitiveCount(ri)) {
                        runCatching {
                            rm.getMaterialInstanceAt(ri, i).setParameter(
                                "baseColorFactor",
                                srgbToLinear(android.graphics.Color.red(savedColour)),
                                srgbToLinear(android.graphics.Color.green(savedColour)),
                                srgbToLinear(android.graphics.Color.blue(savedColour)),
                                1f
                            )
                        }
                    }
                }
                currentModelColour = savedColour
            } else {
                currentModelColour = null
            }
            selectModel(node)
        }
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private fun selectModel(defaultNode: DefaultModelNode) {
        log("SELECT MODEL CALLED")

        selectedModel?.let { currentWrapper ->
            if (isAnimationPlaying) {
                currentWrapper.stopAllAnimations()
            }
            currentWrapper.syncBaseScale()
            currentWrapper.syncBaseRotation()
            val returnedNode = currentWrapper.unwrap()
            if (returnedNode != null && !models.contains(returnedNode)) {
                log("Adding returning model back to model list")
                models.add(returnedNode)
            }
        }

        isAnimationPlaying = false
        isAnimationStarted = false
        selectedModel?.setAnimationPlaying(false)

        dimensionHud.visibility = View.GONE
        models.remove(defaultNode)
        val needsColour = modelSourceFormats[defaultNode.getModeleName()] in setOf("stl", "obj", "ply", "3ds")
        btnModelColour.visibility = if (needsColour) View.VISIBLE else View.GONE
        if (needsColour) {
            updateModelColourSwatch(currentModelColour ?: android.graphics.Color.parseColor("#B2B2B2"))
        }

        val wrapped = defaultNode.wrapAsSelected(scope = lifecycleScope)
        selectedModel = wrapped

        animationToggleButton.alpha = 0.5f

        if (wrapped != null) {
            val profile = profileManager.loadDefault(defaultNode.getModeleName())
            val profileScale = if (profile != null)
                Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
            else
                Float3(1f)

            if (!defaultNode.profileApplied && profile != null) {
                defaultNode.scale = profileScale
                wrapped.applyProfileRotation(Float3(profile.rotationX, profile.rotationY, profile.rotationZ))
                defaultNode.profileApplied = true
            }

            wrapped.setBaseScale(profileScale)
            wrapped.refreshRingScale()

            val currentScale = wrapped.getWrappedNode()?.scale ?: profileScale
            val relX = currentScale.x / profileScale.x
            val relY = currentScale.y / profileScale.y
            val relZ = currentScale.z / profileScale.z
            val relRot = defaultNode.currentRelativeRotation

            modelControls.bindToNodeWithRelativeValues(wrapped, relX, relY, relZ, relRot)
            modelControls.visibility      = View.VISIBLE
            wireframeModeButton.visibility = View.VISIBLE
            wireframeModeButton.alpha      = 0.5f

            isRotationRingVisible = true
            rotationRingToggleButton.alpha      = 1.0f
            rotationRingToggleButton.visibility = View.VISIBLE

            currentAnimationIndex = 0
            isAnimationPlaying = false
            refreshAnimationUI()

            wireframeModeButton.setOnClickListener {
                val isNowVisible = wrapped.toggleDimensions(arSceneView, viewAttachmentManager)
                wireframeModeButton.alpha = if (isNowVisible) 1.0f else 0.5f
                if (isNowVisible) {
                    wrapped.getDimensionOverlay()?.let { overlay ->
                        updateDimensionHud(overlay.getDimensions())
                    }
                    dimensionHud.visibility = View.VISIBLE
                    statusText.text = getString(R.string.status_dimensions_shown)
                } else {
                    dimensionHud.visibility = View.GONE
                    statusText.text = getString(R.string.status_dimensions_hidden)
                }
            }
        }

        statusText.text = getString(R.string.status_model_selected)
    }

    private fun deselectModel() {
        if (isAnimationPlaying) {
            selectedModel?.stopAllAnimations()
            isAnimationPlaying = false
            isAnimationStarted = false
        }

        val returnedNode = selectedModel?.unwrap()
        if (returnedNode != null && !models.contains(returnedNode)) {
            models.add(returnedNode)
        }
        selectedModel = null
        modelControls.visibility            = View.GONE
        wireframeModeButton.visibility      = View.GONE
        wireframeModeButton.alpha      = 0.5f
        btnModelColour.visibility           = View.GONE
        clearModelColourOverride()
        isAnimationPlaying = false
        isAnimationStarted = false
        refreshAnimationUI()
        rotationRingToggleButton.visibility = View.GONE
        selectedModel?.destroyDimensionOverlay()
        dimensionHud.visibility             = View.GONE
        currentAnimationIndex               = 0
        statusText.text = getString(R.string.status_model_deselected)
    }

    // ── Dimension HUD ─────────────────────────────────────────────────────────

    private fun updateDimensionHud(dims: Triple<Float, Float, Float>) {
        val (w, h, d) = dims
        val (wVal, suffix) = unit.convert(w)
        val (hVal, _)      = unit.convert(h)
        val (dVal, _)      = unit.convert(d)
        dimW.text = "W: ${"%.1f".format(wVal)} $suffix"
        dimH.text = "H: ${"%.1f".format(hVal)} $suffix"
        dimD.text = "D: ${"%.1f".format(dVal)} $suffix"
    }

    // ── Profile dialog ────────────────────────────────────────────────────────

    private fun showProfileDialog() {
        val selected  = selectedModel ?: return
        val wrapped   = selected.getWrappedNode() ?: return
        val modelName = wrapped.getModeleName()

        val dialog = ProfilePickerDialog.newInstance(wrapped.modelId, modelName)
        dialog.activeProfileName = selectedModel?.activeProfileName

        dialog.getCurrentProfile = {
            ModelProfile(
                scaleX    = wrapped.scale.x,
                scaleY    = wrapped.scale.y,
                scaleZ    = wrapped.scale.z,
                rotationX = selected.rotation.x,
                rotationY = selected.rotation.y,
                rotationZ = selected.rotation.z
            )
        }

        dialog.onDefaultProfileSaved = onDefaultProfileSaved@{ savedProfile ->
            val sel = selectedModel ?: return@onDefaultProfileSaved
            val wrp = sel.getWrappedNode() ?: return@onDefaultProfileSaved
            sel.activeProfileName = ProfilePickerDialog.SLOT_DEFAULT
            wrp.scale = Float3(savedProfile.scaleX, savedProfile.scaleY, savedProfile.scaleZ)
            sel.applyProfileRotation(Float3(savedProfile.rotationX, savedProfile.rotationY, savedProfile.rotationZ))
            sel.syncBaseScale()
            sel.syncBaseRotation()
            sel.refreshRingScale()
            modelControls.bindToNode(sel)
            modelControls.resetSlidersToNeutral()
            if (dimensionHud.visibility == View.VISIBLE) {
                sel.getDimensionOverlay()?.let { updateDimensionHud(it.getDimensions()) }
            }
            statusText.text = getString(R.string.status_profile_saved)
        }

        dialog.onNamedProfileSaved = onNamedProfileSaved@{ savedName, savedProfile ->
            val sel = selectedModel ?: return@onNamedProfileSaved
            val wrp = sel.getWrappedNode() ?: return@onNamedProfileSaved
            sel.activeProfileName = savedName
            wrp.scale = Float3(savedProfile.scaleX, savedProfile.scaleY, savedProfile.scaleZ)
            sel.applyProfileRotation(Float3(savedProfile.rotationX, savedProfile.rotationY, savedProfile.rotationZ))
            sel.syncBaseScale()
            sel.syncBaseRotation()
            sel.refreshRingScale()
            modelControls.bindToNode(sel)
            modelControls.resetSlidersToNeutral()
            if (dimensionHud.visibility == View.VISIBLE) {
                sel.getDimensionOverlay()?.let { updateDimensionHud(it.getDimensions()) }
            }
            statusText.text = getString(R.string.status_profile_saved)
        }

        dialog.onLoadProfile = { profile ->
            val loadedName = dialog.lastLoadedProfileName
            selected.activeProfileName = loadedName
            wrapped.scale = Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
            selected.applyProfileRotation(Float3(profile.rotationX, profile.rotationY, profile.rotationZ))
            selected.syncBaseScale()
            selected.syncBaseRotation()
            selected.refreshRingScale()
            modelControls.bindToNode(selected)
            modelControls.resetSlidersToNeutral()
            if (dimensionHud.visibility == View.VISIBLE) {
                selected.getDimensionOverlay()?.let { updateDimensionHud(it.getDimensions()) }
            }
        }

        dialog.onStatusUpdate = { message -> statusText.text = message }

        dialog.show(supportFragmentManager, "ProfilePickerDialog")
    }

    // ── Model-picker sheet ────────────────────────────────────────────────────

    private fun showModelPicker() {
        modelPickerPopup.onModelPicked = { picked ->
            activeModelPath = picked.modelPath
            activeModelIsAsset = picked.isAsset
            activeModelSourceFormat = picked.sourceFormat

            val label = picked.modelPath.substringAfterLast('/').substringBeforeLast('.')
            statusText.text = getString(R.string.model_picker_prompt, label)
        }
        modelPickerPopup.show(btnLibrary, activeModelPath)
    }

    // ── Model colour helpers ──────────────────────────────────────────────────

    private fun updateModelColourSwatch(@androidx.annotation.ColorInt color: Int) {
        (modelColourBgRect.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(color) ?: modelColourBgRect.setBackgroundColor(color)
        (modelColourPatchRect.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(color) ?: modelColourPatchRect.setBackgroundColor(color)
    }


    private fun applyModelColour(@androidx.annotation.ColorInt color: Int) {
        val node = selectedModel?.getWrappedNode() ?: return
        val rm = arSceneView.engine.renderableManager

        for (entity in node.modelInstance.asset.renderableEntities) {
            val ri = rm.getInstance(entity)
            if (ri == 0) continue
            for (i in 0 until rm.getPrimitiveCount(ri)) {
                runCatching {
                    rm.getMaterialInstanceAt(ri, i).setParameter(
                        "baseColorFactor",
                        srgbToLinear(android.graphics.Color.red(color)),
                        srgbToLinear(android.graphics.Color.green(color)),
                        srgbToLinear(android.graphics.Color.blue(color)),
                        1f
                    )
                }
            }
        }

        currentModelColour = color
        updateModelColourSwatch(color)

        val modelName = selectedModel?.getWrappedNode()?.getModeleName() ?: return
        profileManager.saveColorOverride(modelName, color)
    }

    private fun srgbToLinear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f
        else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    }

    private fun restoreModelMaterials() {
        val grey = android.graphics.Color.parseColor("#B2B2B2")
        val node = selectedModel?.getWrappedNode() ?: return
        val rm = arSceneView.engine.renderableManager

        for (entity in node.modelInstance.asset.renderableEntities) {
            val ri = rm.getInstance(entity)
            if (ri == 0) continue
            for (i in 0 until rm.getPrimitiveCount(ri)) {
                runCatching {
                    rm.getMaterialInstanceAt(ri, i).setParameter(
                        "baseColorFactor",
                        srgbToLinear(android.graphics.Color.red(grey)),
                        srgbToLinear(android.graphics.Color.green(grey)),
                        srgbToLinear(android.graphics.Color.blue(grey)),
                        1f
                    )
                }
            }
        }

        currentModelColour = null
        updateModelColourSwatch(grey)
        val modelName = node.getModeleName()
        profileManager.clearColorOverride(modelName)
    }

    private fun clearModelColourOverride() {
        currentModelColour = null
    }

    private fun showModelColourPicker() {
        val initial = currentModelColour ?: android.graphics.Color.parseColor("#B2B2B2")
        val inner = android.app.Dialog(this)
        val dp = resources.displayMetrics.density

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.screen_background))
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 20 * dp, 20 * dp, 0f, 0f, 0f, 0f)
            }
        }
        root.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (40 * dp).toInt(), (4 * dp).toInt()
            ).also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL; it.bottomMargin = (16 * dp).toInt() }
            setBackgroundColor(getColor(R.color.background_tint))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.model_colour_picker_title)
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        })
        val previewDot = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (40 * dp).toInt(), (40 * dp).toInt()
            ).also { it.marginEnd = (12 * dp).toInt() }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(initial)
            }
        }
        root.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (16 * dp).toInt() }
            addView(previewDot)
            addView(android.widget.TextView(this@ARActivity).apply {
                text = getString(R.string.studio_color_picker_live_preview)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            })
        })
        val picker = com.example.WymiAR.Helpers.HsvColorPicker(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (200 * dp).toInt()
            ).also { it.bottomMargin = (8 * dp).toInt() }
            setColor(initial)
        }
        root.addView(picker)
        var pendingColour = initial

        picker.onColorChanged = { color ->
            pendingColour = color
            (previewDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
        }

        root.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8 * dp).toInt() }

            addView(android.widget.Button(this@ARActivity).apply {
                text = getString(R.string.model_colour_restore)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.marginEnd = (8 * dp).toInt() }
                setOnClickListener {
                    restoreModelMaterials()
                    inner.dismiss()
                }
            })

            addView(android.widget.Button(this@ARActivity).apply {
                text = getString(R.string.confirm)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener {
                    applyModelColour(pendingColour)
                    inner.dismiss()
                }
            })
        })
        inner.setContentView(root)
        inner.show()
        inner.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(android.view.Gravity.BOTTOM)
            attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
        }
    }

    private fun deleteSelectedModel() {
        val selected   = selectedModel ?: return
        val anchorNode = selected.parent as? AnchorNode

        if (isAnimationPlaying) {
            selected.stopAllAnimations()
            isAnimationPlaying = false
        }

        anchorNode?.anchor?.detach()
        anchorNode?.parent = null

        clearModelColourOverride()

        val default   = selected.unwrap()
        val modelName = default?.getModeleName()
        models.remove(default)
        modelSourceFormats.remove(default?.getModeleName())
        selectedModel = null
        modelControls.visibility            = View.GONE
        wireframeModeButton.visibility      = View.GONE
        btnModelColour.visibility           = View.GONE
        isAnimationPlaying = false
        isAnimationStarted = false
        refreshAnimationUI()
        rotationRingToggleButton.visibility = View.GONE
        dimensionHud.visibility             = View.GONE
        currentAnimationIndex               = 0

        statusText.text = getString(R.string.status_model_deleted, modelName ?: "")
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    private fun distanceMeters(a: Float3, b: Float3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun getFingerSpacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 10f
        return try {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            sqrt(x * x + y * y)
        } catch (e: IllegalArgumentException) { 10f }
    }

    private fun startScanAnimation() {
        val icon = planeScanOverlay.findViewById<ImageView>(R.id.scanPhoneIcon)

        val tilt = ObjectAnimator.ofFloat(icon, "rotation", -20f, 20f).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }

        val float = ObjectAnimator.ofFloat(icon, "translationY", 0f, -12f).apply {
            duration = 1200
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }

        AnimatorSet().apply {
            playTogether(tilt, float)
            start()
        }.also { icon.tag = it }
        scanTimeoutRunnable = Runnable { hideScanOverlay() }.also {
            planeScanOverlay.postDelayed(it, 5_000L)
        }
    }

    private fun hideScanOverlay() {
        if (hasDetectedFirstPlane) return
        hasDetectedFirstPlane = true

        scanTimeoutRunnable?.let { planeScanOverlay.removeCallbacks(it) }
        scanTimeoutRunnable = null

        val icon = planeScanOverlay.findViewById<ImageView>(R.id.scanPhoneIcon)
        (icon?.tag as? AnimatorSet)?.cancel()

        planeScanOverlay.animate()
            .alpha(0f)
            .setDuration(600)
            .withEndAction { planeScanOverlay.visibility = View.GONE }
            .start()
    }

    override fun onSupportNavigateUp(): Boolean {
        closeScene()
        return true
    }

    private fun closeScene() {
        if (isClosing) return
        isClosing = true
        arSceneView.onSessionUpdated = null
        arSceneView.onTouchEvent = null
        arSceneView.session?.pause()

        arSceneView.lifecycle = null

        if (isAnimationPlaying) {
            selectedModel?.stopAllAnimations()
            isAnimationPlaying = false
            isAnimationStarted = false
        }

        scanTimeoutRunnable?.let { planeScanOverlay.removeCallbacks(it) }
        scanTimeoutRunnable = null

        val icon = planeScanOverlay.findViewById<ImageView>(R.id.scanPhoneIcon)
        (icon?.tag as? AnimatorSet)?.cancel()

        planeScanOverlay.visibility = View.GONE

        selectedModel?.destroyDimensionOverlay()
        selectedModel?.let { deselectModel() }
        selectedModel = null

        placedModelNodes.forEach { anchorNode ->
            anchorNode.childNodes.toList().forEach { child ->
                (child as? DefaultModelNode)?.destroy()
                child.parent = null
            }
            anchorNode.parent = null
            anchorNode.anchor?.detach()
        }
        placedModelNodes.clear()
        models.clear()

        placedMeasureNodes.forEach { anchorNode ->
            anchorNode.childNodes.toList().forEach { it.parent = null }
            anchorNode.parent = null
            anchorNode.anchor?.detach()
        }
        placedMeasureNodes.clear()

        try { arSceneView.engine.flushAndWait() } catch (_: Exception) {}


        planeGridRenderer.destroy()
        arSceneView.destroy()
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (!isClosing) {
            arSceneView.session?.pause()
        }
        viewAttachmentManager.onPause()
    }

    override fun onDestroy() {
        if (!isClosing) {
            arSceneView.onSessionUpdated = null
            arSceneView.onTouchEvent = null
            arSceneView.session?.pause()
            arSceneView.lifecycle = null

            if (isAnimationPlaying) { selectedModel?.stopAllAnimations(); isAnimationPlaying = false; isAnimationStarted = false }
            selectedModel?.let { deselectModel() }
            selectedModel = null

            scanTimeoutRunnable?.let { planeScanOverlay.removeCallbacks(it) }
            scanTimeoutRunnable = null

            placedModelNodes.forEach { anchorNode ->
                anchorNode.childNodes.toList().forEach { child ->
                    (child as? DefaultModelNode)?.destroy()
                    child.parent = null
                }
                anchorNode.parent = null
                anchorNode.anchor?.detach()
            }
            placedModelNodes.clear()
            models.clear()

            placedMeasureNodes.forEach { anchorNode ->
                anchorNode.childNodes.toList().forEach { it.parent = null }
                anchorNode.parent = null
                anchorNode.anchor?.detach()
            }
            placedMeasureNodes.clear()

            try { arSceneView.engine.flushAndWait() } catch (_: Exception) {}

            planeGridRenderer.destroy()
            arSceneView.destroy()
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isMeasureToolActive) pushOverlayState()
    }
}