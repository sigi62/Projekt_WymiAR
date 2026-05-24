package com.example.pracazaliczeniowa.Activities

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
import com.example.pracazaliczeniowa.Objects.AppSettings
import com.example.pracazaliczeniowa.Helpers.ModelPickerPopup
import com.example.pracazaliczeniowa.Managers.ModelProfile
import com.example.pracazaliczeniowa.Managers.ProfileManager
import com.example.pracazaliczeniowa.Nodes.DefaultModelNode
import com.example.pracazaliczeniowa.Nodes.PlaneGridRenderer
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode
import com.example.pracazaliczeniowa.Helpers.WallMagnetMenuPopup
import com.example.pracazaliczeniowa.Objects.PlaneMode
import com.example.pracazaliczeniowa.Objects.DistanceUnit
import com.example.pracazaliczeniowa.Overlays.MeasureTapeOverlayView
import com.example.pracazaliczeniowa.Overlays.ModelControlOverlayView
import com.example.pracazaliczeniowa.R
import com.example.pracazaliczeniowa.Dialogs.ProfilePickerDialog
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.atan2
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import io.github.sceneview.node.Node
import java.io.File


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
    /** Currently applied override colour on the selected model; null = no override. */
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

    // ── Animation state ──────────────────────────────
    private var isAnimationPlaying: Boolean = false  // true = not paused
    private var isAnimationStarted: Boolean = false  // true = play was hit (not stopped)

    // ── Measure state ────────────────────────────────────────────────────────
    /**
     * Committed line segments – each is a pair of AnchorNodes that are fully
     * anchored to the scene and owned by [placedMeasureNodes].
     * Hard-limited to MAX_MEASURE_LINES (5) entries.
     */
    private val measureLines = mutableListOf<Pair<AnchorNode, AnchorNode>>()

    /**
     * The node placed on the first tap of a new line, waiting for a second tap.
     * Null when no line is in progress.
     */
    private var pendingMeasureNode: AnchorNode? = null

    companion object {
        /** Maximum number of simultaneous measurement lines. */
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

    /**
     * Path of the active model.
     * May be an asset-relative path ("models/cat.glb") or an absolute path
     * ("/data/.../models/cat.glb") depending on [activeModelIsAsset].
     */
    private var activeModelPath: String    = "models/cat.glb"
    private var activeModelIsAsset: Boolean = true
    /** Source format recorded at import time; null for assets and direct GLB imports. */
    private var activeModelSourceFormat: String? = null
    private val modelSourceFormats = mutableMapOf<String, String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        activeModelPath    = intent.getStringExtra(LibraryActivity.Companion.EXTRA_MODEL_PATH)    ?: "models/cat.glb"
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
        arSceneView.onSessionUpdated = { _, _ ->
            if (!isClosing) {
                if (arSceneView.session != null) {
                    planeGridRenderer.update(planeMode)
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

        // ── Wall-magnet toggle click ───────────────────────────────────────────
        wallMagnetButton.setOnClickListener {
            if (wallMagnetMenuPopup.isShowing()) {
                wallMagnetMenuPopup.dismiss()
            } else {
                wallMagnetMenuPopup.onModeSelected = { mode -> setPlaneMode(mode) }
                wallMagnetMenuPopup.show(wallMagnetButton, planeMode)
            }
        }


        // ── Animation toggle click ────────────────────────────────────────────
        // ── Play / Stop ───────────────────────────────────────────────────────
        animationToggleButton.setOnClickListener {
            val wrapped = selectedModel ?: return@setOnClickListener
            if (isAnimationStarted) {
                // STOP — reset everything
                wrapped.stopAllAnimations()
                isAnimationStarted = false
                isAnimationPlaying = false
                currentAnimationIndex = 0
            } else {
                // PLAY — start from frame 0
                isAnimationStarted = true
                isAnimationPlaying = true
                wrapped.resumeAnimation(currentAnimationIndex)
            }
            refreshAnimationUI()
            statusText.text = if (isAnimationStarted) getString(R.string.animation_toggle_on)
            else getString(R.string.animation_toggle_off)
        }

        // ── Pause / Resume ────────────────────────────────────────────────────
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

        // ── Next animation ────────────────────────────────────────────────────
        animationNextButton.setOnClickListener {
            val wrapped = selectedModel ?: return@setOnClickListener
            val count = wrapped.getAnimationCount()
            if (count < 2) return@setOnClickListener
            currentAnimationIndex = (currentAnimationIndex + 1) % count
            wrapped.playAnimation(currentAnimationIndex)
            statusText.text = "Animation ${currentAnimationIndex + 1} / $count"
        }

        // ── Rotation ring toggle click ────────────────────────────────────────
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
                            statusText.text = "Move camera slowly to detect surfaces…"
                            return@onTouchEvent true
                        }
                        if (arHit != null) placeMeasurePoint(arHit)
                        else statusText.text = "Tap on a detected surface (grid area)"
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
        // Play/Stop — always visible when model has animations; bright when started
        animationToggleButton.visibility = if (hasAnim) View.VISIBLE else View.GONE
        animationToggleButton.alpha      = if (isAnimationStarted) 1.0f else 0.5f
        // Pause/Resume — visible once started, until stopped; icon reflects paused state
        animationPauseButton.visibility  = if (hasAnim && isAnimationStarted) View.VISIBLE else View.GONE
        animationPauseButton.alpha       = if (isAnimationPlaying) 1.0f else 0.5f
        // Next — visible once started, until stopped, only when >1 track
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
            // ── Hide nodes and overlay, but keep all state intact ──────────────
            setMeasureNodesVisible(false)
            measureOverlay.visibility = View.INVISIBLE   // hides lines but preserves state
            btnMeasureDelete.visibility = View.GONE
            btnMeasureRevert.visibility = View.GONE
            statusText.text = getString(
                R.string.status_active_model,
                activeModelPath.substringAfterLast('/').substringBeforeLast('.'))
        } else {
            deselectModel()
            // ── Restore nodes and overlay ──────────────────────────────────────
            setMeasureNodesVisible(true)
            measureOverlay.visibility = View.VISIBLE
            pushOverlayState()   // re-sync overlay with existing measureLines
            btnMeasureDelete.visibility = View.VISIBLE
            btnMeasureRevert.visibility = View.VISIBLE
            statusText.text = if (measureLines.isEmpty() && pendingMeasureNode == null)
                getString(R.string.measure_tap_first)
            else
                getString(R.string.measure_tap_second).takeIf { pendingMeasureNode != null }
                    ?: "Measurements restored · tap to add more"
        }
    }

    private fun setMeasureNodesVisible(visible: Boolean) {
        placedMeasureNodes.forEach { it.isVisible = visible }
    }

    // ── Measure point placement ───────────────────────────────────────────────

    private fun placeMeasurePoint(hitResult: HitResult) {
        val pending = pendingMeasureNode

        if (pending == null) {
            // ── First tap: enforce line limit ─────────────────────────────────
            if (measureLines.size >= MAX_MEASURE_LINES) {
                statusText.text = "Maximum of $MAX_MEASURE_LINES measurements reached. Delete one to continue."
                return
            }

            // Create the first anchor of a new line
            val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
            arSceneView.addChildNode(anchorNode)
            placedMeasureNodes.add(anchorNode)

            pendingMeasureNode = anchorNode
            pushOverlayState()
            statusText.text = getString(R.string.measure_tap_second)

        } else {
            // ── Second tap: commit the line ───────────────────────────────────
            val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
            arSceneView.addChildNode(anchorNode)
            placedMeasureNodes.add(anchorNode)

            measureLines.add(Pair(pending, anchorNode))
            pendingMeasureNode = null
            pushOverlayState()

            // Report distance for the just-committed line
            val a = pending.worldPosition.let { Float3(it.x, it.y, it.z) }
            val b = anchorNode.worldPosition.let { Float3(it.x, it.y, it.z) }
            val dist = distanceMeters(a, b)
            val (value, suffix) = unit.convert(dist)

            val linesLeft = MAX_MEASURE_LINES - measureLines.size
            statusText.text = if (linesLeft > 0)
                getString(R.string.measure_distance, value, suffix) + " · tap to add another"
            else
                getString(R.string.measure_distance, value, suffix) + " · limit reached"
        }
    }

    /**
     * Undo the last placed point:
     * - If there is a pending (orphan) first-tap node, remove it.
     * - Otherwise remove the second point of the last committed line (which
     *   turns it back into a pending node — the first point stays anchored and
     *   the user can tap a new second point to replace it).
     */
    private fun revertLastMeasurePoint() {
        val pending = pendingMeasureNode
        if (pending != null) {
            // Remove the pending first-tap node
            pending.anchor?.detach()
            pending.parent = null
            placedMeasureNodes.remove(pending)
            pendingMeasureNode = null
            pushOverlayState()

            val linesCount = measureLines.size
            statusText.text = if (linesCount == 0)
                getString(R.string.measure_tap_first)
            else
                "Point removed · $linesCount line${if (linesCount != 1) "s" else ""} remaining"
        } else if (measureLines.isNotEmpty()) {
            // Pop the last committed line's second point; turn it back to pending
            val lastLine = measureLines.removeAt(measureLines.size - 1)
            val (nodeA, nodeB) = lastLine

            // Detach and remove nodeB from the scene
            nodeB.anchor?.detach()
            nodeB.parent = null
            placedMeasureNodes.remove(nodeB)

            // nodeA becomes the pending node again
            pendingMeasureNode = nodeA
            pushOverlayState()
            statusText.text = getString(R.string.measure_tap_second)
        } else {
            statusText.text = "Nothing to undo."
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

    /** Push the current measure state to the overlay so it redraws correctly. */
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

        // Icon rotation and status text per mode
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
                statusText.text = getString(R.string.status_all_surfaces)  // add this string
            }
            PlaneMode.OFF -> {
                wallMagnetButton.rotation = 0f
                wallMagnetButton.alpha    = 0.35f
                statusText.text = getString(R.string.status_planes_hidden) // add this string
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
                modelPath             = activeModelPath,
                modelInstance         = modelInstance,
                scope                 = lifecycleScope,
                sceneView             = arSceneView,
                viewAttachmentManager = viewAttachmentManager
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

            // Apply the default profile scale before wrapping so the model
            // is the right size from the first frame. Rotation is applied
            // after wrapping via applyProfileRotation in selectModel so the
            // ring is correctly positioned from the start.
            val profile = profileManager.loadDefault(node.getModeleName())
            if (profile != null) {
                node.scale = Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
            }

            // Load any saved colour override. We need to apply it directly to
            // the node here (before selectModel wraps it) because applyModelColour
            // operates on selectedModel which isn't set yet.
            val savedColour = profileManager.loadColorOverride(node.getModeleName())
            if (savedColour != null &&
                modelSourceFormats[node.getModeleName()] in setOf("stl", "obj", "ply", "3ds")) {
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
                // Seed currentModelColour so the picker opens pre-filled and
                // selectModel shows the right swatch colour immediately.
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

        // ── Model colour button visibility ────────────────────────────────────
        // Show only for texture-less formats (STL / PLY / 3DS) that received
        // Assimp's grey default PBR material; hidden for everything else.
        // Use the source format recorded at import time rather than the
        // file path, which is always .glb at this point.
        val needsColour = modelSourceFormats[defaultNode.getModeleName()] in setOf("stl", "obj", "ply", "3ds")
        btnModelColour.visibility = if (needsColour) View.VISIBLE else View.GONE
        if (needsColour) {
            updateModelColourSwatch(currentModelColour ?: android.graphics.Color.parseColor("#B2B2B2"))
        }

        val wrapped = defaultNode.wrapAsSelected(scope = lifecycleScope)
        selectedModel = wrapped

        animationToggleButton.alpha = 0.5f

        if (wrapped != null) {
            // The base is always the default profile (or raw 1.0 if none).
            // Sliders are always multipliers ON TOP of this base.
            val profile = profileManager.loadDefault(defaultNode.getModeleName())
            val profileScale = if (profile != null)
                Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
            else
                Float3(1f)



            // First-time placement: the pre-wrap node.rotation set in placeModel
            // is already transferred to the wrapper by wrapAsSelected via worldQuaternion.
            // Only the scale needs re-applying here; mark as applied.
            if (!defaultNode.profileApplied && profile != null) {
                defaultNode.scale = profileScale
                // Rotation was already baked into the wrapper's worldQuaternion by
                // wrapAsSelected; apply it properly through the wrapper so the ring follows.
                wrapped.applyProfileRotation(Float3(profile.rotationX, profile.rotationY, profile.rotationZ))
                defaultNode.profileApplied = true
            }

            // Always lock baseScale to the profile, never to the live scale.
            // This means slider 1.0 always = profile scale, slider 1.6 = 1.6× the profile.
            wrapped.setBaseScale(profileScale)
            wrapped.refreshRingScale()

            // Now seed sliders: current scale divided by baseScale = the relative multiplier
            // the sliders should show.
            val currentScale = wrapped.getWrappedNode()?.scale ?: profileScale
            val relX = currentScale.x / profileScale.x
            val relY = currentScale.y / profileScale.y
            val relZ = currentScale.z / profileScale.z
            val relRot = defaultNode.currentRelativeRotation

            modelControls.bindToNodeWithRelativeValues(wrapped, relX, relY, relZ, relRot)
            // No resetSlidersToNeutral — sliders now correctly show the relative value
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
        // Clear colour override state when deselecting so a freshly placed
        // instance of the same model starts with no override applied.
        clearModelColourOverride()
        isAnimationPlaying = false
        isAnimationStarted = false
        refreshAnimationUI()
        rotationRingToggleButton.visibility = View.GONE
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

        val dialog = ProfilePickerDialog.newInstance(modelName)
        // Tell the dialog which profile slot is currently loaded so it can
        // show the "active" indicator on the right row.
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
            activeModelSourceFormat = picked.sourceFormat  // ← ADD THIS

            val label = picked.modelPath.substringAfterLast('/').substringBeforeLast('.')
            statusText.text = getString(R.string.model_picker_prompt, label)
        }
        modelPickerPopup.show(btnLibrary, activeModelPath)
    }

    // ── Model colour helpers ──────────────────────────────────────────────────

    /** Repaints both swatch views on the colour button to reflect [color]. */
    private fun updateModelColourSwatch(@androidx.annotation.ColorInt color: Int) {
        (modelColourBgRect.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(color) ?: modelColourBgRect.setBackgroundColor(color)
        (modelColourPatchRect.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(color) ?: modelColourPatchRect.setBackgroundColor(color)
    }

    /**
     * Applies [color] to every primitive of the currently selected model by
     * mutating the existing MaterialInstance parameters in place, then persists
     * it via [ProfileManager] so ModelPreviewActivity loads it on next open.
     * Never creates or destroys MaterialInstances — safe to call at any time.
     */
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

        // Persist so ModelPreviewActivity and future AR placements load the same colour.
        val modelName = selectedModel?.getWrappedNode()?.getModeleName() ?: return
        profileManager.saveColorOverride(modelName, color)
    }

    private fun srgbToLinear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f
        else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    }

    /** Resets the model colour back to Assimp's default grey by mutating parameters in place,
     *  then clears the persisted override so ModelPreviewActivity also returns to grey. */
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

        // Clear the persisted override so ModelPreviewActivity and future AR
        // placements both start with the default grey.
        val modelName = node.getModeleName()
        profileManager.clearColorOverride(modelName)
    }

    /** Resets colour override tracking state — no Filament resources to destroy. */
    private fun clearModelColourOverride() {
        currentModelColour = null
    }

    /**
     * Shows a bottom-sheet colour picker for the selected model's surface colour.
     * Only reachable when [btnModelColour] is visible (texture-less models).
     */
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
        // Drag handle
        root.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (40 * dp).toInt(), (4 * dp).toInt()
            ).also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL; it.bottomMargin = (16 * dp).toInt() }
            setBackgroundColor(getColor(R.color.background_tint))
        })
        // Title
        root.addView(android.widget.TextView(this).apply {
            text = getString(R.string.model_colour_picker_title)
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (12 * dp).toInt() }
        })
        // Live preview dot
        val previewDot = android.view.View(this).apply {
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
        // Colour picker wheel
        val picker = com.example.pracazaliczeniowa.Helpers.HsvColorPicker(this).apply {
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

        // Restore | Confirm button row
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

    // ── Delete model from scene ───────────────────────────────────────────────

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

    private fun calculateAngle(touchX: Float, touchY: Float, node: Node): Float {
        val worldPos  = node.worldPosition
        val screenPos = arSceneView.cameraNode.worldToView(worldPos)
        val dx = touchX - screenPos.x
        val dy = touchY - screenPos.y
        return Math.toDegrees(Math.atan2((-dy).toDouble(), dx.toDouble())).toFloat()
    }

    override fun onSupportNavigateUp(): Boolean {
        closeScene()
        return true
    }

    private fun closeScene() {
        if (isClosing) return
        isClosing = true

        // ── 1. Kill all callbacks and pause the AR session immediately ────────
        // This must happen BEFORE any node teardown so that no session-update
        // or touch callback can fire against a partially-destroyed scene graph,
        // and so ARCore stops trying to acquire camera frames.
        arSceneView.onSessionUpdated = null
        arSceneView.onTouchEvent = null
        arSceneView.session?.pause()

        // Unbind the SceneView from the Activity lifecycle so its internal
        // lifecycle observer doesn't call pause()/destroy() a second time
        // and trigger a double-free in the native layer.
        arSceneView.lifecycle = null

        if (isAnimationPlaying) {
            selectedModel?.stopAllAnimations()
            isAnimationPlaying = false
            isAnimationStarted = false
        }

        // ── 2. Deselect cleanly (destroys rotation ring, overlays, etc.) ──────
        selectedModel?.let { deselectModel() }
        selectedModel = null

        // ── 3. Remove all model nodes from the scene graph ────────────────────
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

        // ── 4. Remove all measure nodes ───────────────────────────────────────
        placedMeasureNodes.forEach { anchorNode ->
            anchorNode.childNodes.toList().forEach { it.parent = null }
            anchorNode.parent = null
            anchorNode.anchor?.detach()
        }
        placedMeasureNodes.clear()

        // ── 5. Flush GPU work and tear down the engine ────────────────────────
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
            // Same order as closeScene(): stop the session before touching nodes.
            arSceneView.onSessionUpdated = null
            arSceneView.onTouchEvent = null
            arSceneView.session?.pause()
            arSceneView.lifecycle = null

            if (isAnimationPlaying) { selectedModel?.stopAllAnimations(); isAnimationPlaying = false; isAnimationStarted = false }
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
        }
        viewAttachmentManager.onPause()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // ARSceneView's Surface resizes automatically via its SurfaceHolder.
        // We just need to re-push any overlay state that depends on screen dimensions.
        if (isMeasureToolActive) pushOverlayState()
    }
}