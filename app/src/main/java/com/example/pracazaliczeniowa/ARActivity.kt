package com.example.pracazaliczeniowa

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
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
import com.example.pracazaliczeniowa.Helpers.AppSettings
import com.example.pracazaliczeniowa.Helpers.ModelPickerPopup
import com.example.pracazaliczeniowa.Helpers.ModelProfile
import com.example.pracazaliczeniowa.Helpers.ProfileManager
import com.example.pracazaliczeniowa.Helpers.ProfilePickerDialog

import com.example.pracazaliczeniowa.Nodes.DefaultModelNode
import com.example.pracazaliczeniowa.Nodes.PlaneGridRenderer
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode

import com.example.pracazaliczeniowa.Helpers.DistanceUnit
import com.example.pracazaliczeniowa.Overlays.MeasureTapeOverlayView
import com.example.pracazaliczeniowa.Overlays.ModelControlOverlayView
import com.google.ar.core.Config
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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
    private lateinit var profileManager: ProfileManager

    private lateinit var settings: AppSettings
    private lateinit var measureModeButton: ImageButton
    private lateinit var wireframeModeButton: ImageButton
    private lateinit var animationToggleButton: ImageButton
    private lateinit var rotationRingToggleButton: ImageButton
    private lateinit var wallMagnetButton: ImageButton

    // true - VERTICAL, false - HORIZONTAL planes
    private var isWallMagnetVertical: Boolean = false

    private lateinit var btnLibrary : ImageButton
    private var unit: DistanceUnit = DistanceUnit.CENTIMETERS
    private lateinit var settingsButton: ImageButton
    private lateinit var backButton: ImageButton

    // ── Measure-mode action buttons ──────────────────────────────────────────
    private lateinit var btnMeasureDelete: ImageButton
    private lateinit var btnMeasureRevert: ImageButton

    private var isRotationRingVisible: Boolean = true
    private var isClosing: Boolean = false

    private lateinit var planeGridRenderer: PlaneGridRenderer

    // ── Dimension HUD views ──────────────────────────────────────────────────
    private lateinit var dimensionHud: View
    private lateinit var dimW: TextView
    private lateinit var dimH: TextView
    private lateinit var dimD: TextView

    private var isMeasureToolActive: Boolean = false

    // ── Animation state ──────────────────────────────
    private var isAnimationPlaying: Boolean = false

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
    private var touchStartPos           = android.graphics.PointF()
    private val MOVE_THRESHOLD          = 20f
    private var initialModelRotationY   = 0f

    /**
     * Path of the active model.
     * May be an asset-relative path ("models/cat.glb") or an absolute path
     * ("/data/.../models/cat.glb") depending on [activeModelIsAsset].
     */
    private var activeModelPath: String    = "models/cat.glb"
    private var activeModelIsAsset: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        activeModelPath    = intent.getStringExtra(LibraryActivity.EXTRA_MODEL_PATH)    ?: "models/cat.glb"
        activeModelIsAsset = intent.getBooleanExtra(LibraryActivity.EXTRA_MODEL_IS_ASSET, true)

        profileManager = ProfileManager(this)
        settings = AppSettings(this)
        unit = settings.distanceUnit
        modelPickerPopup = ModelPickerPopup(this)

        arSceneView           = findViewById(R.id.arSceneView)
        statusText            = findViewById(R.id.statusText)
        modelControls         = findViewById(R.id.modelControls)
        measureOverlay        = findViewById(R.id.measureOverlay)
        backButton                = findViewById(R.id.btnBack)
        measureModeButton         = findViewById(R.id.btnMeasureTapeModeToggle)
        wireframeModeButton       = findViewById(R.id.btnWireframeToggle)
        animationToggleButton     = findViewById(R.id.btnAnimationToggle)
        rotationRingToggleButton  = findViewById(R.id.btnRotationRingToggle)
        wallMagnetButton          = findViewById(R.id.btnWallMagnet)
        settingsButton            = findViewById(R.id.btnSettings)
        btnLibrary                = findViewById<ImageButton>(R.id.btnLibrary)

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
        }

        backButton.setOnClickListener { closeScene() }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnLibrary.setOnClickListener { showModelPicker() }
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
            planeGridRenderer.update(isWallMagnetVertical)
        }

        lifecycleScope.launch {
            val env = arSceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")
            arSceneView.indirectLight = env?.indirectLight
            arSceneView.skybox        = env?.skybox
        }

        modelControls.onSaveRequested   = { showProfileDialog() }
        modelControls.onDeleteRequested = { deleteSelectedModel() }

        modelControls.visibility            = View.GONE
        wireframeModeButton.visibility      = View.GONE
        animationToggleButton.visibility    = View.GONE
        rotationRingToggleButton.visibility = View.GONE

        measureOverlay.attach(arSceneView)
        measureOverlay.setUnit(unit)

        measureModeButton.alpha = 0.5f
        measureModeButton.setOnClickListener { toggleMeasureTool() }

        // ── Wall-magnet toggle click ───────────────────────────────────────────
        wallMagnetButton.setOnClickListener { toggleWallMagnetMode() }

        // ── Animation toggle click ────────────────────────────────────────────
        animationToggleButton.setOnClickListener {
            val wrapped = selectedModel ?: return@setOnClickListener
            isAnimationPlaying = !isAnimationPlaying
            wrapped.setAnimationPlaying(isAnimationPlaying)
            animationToggleButton.alpha = if (isAnimationPlaying) 1.0f else 0.5f
            statusText.text = if (isAnimationPlaying) getString(R.string.animation_toggle_on) else getString(R.string.animation_toggle_off)
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

            val allowedPlaneTypes: Set<Plane.Type> = if (isWallMagnetVertical)
                setOf(Plane.Type.VERTICAL)
            else
                setOf(Plane.Type.HORIZONTAL_UPWARD_FACING, Plane.Type.HORIZONTAL_DOWNWARD_FACING)

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

    override fun onResume() {
        super.onResume()
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
            clearMeasurements()
            btnMeasureDelete.visibility = View.GONE
            btnMeasureRevert.visibility = View.GONE
            statusText.text = getString(R.string.status_active_model,
                activeModelPath.substringAfterLast('/').substringBeforeLast('.'))
        } else {
            deselectModel()
            btnMeasureDelete.visibility = View.VISIBLE
            btnMeasureRevert.visibility = View.VISIBLE
            statusText.text = getString(R.string.measure_tap_first)
        }
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

    private fun toggleWallMagnetMode() {
        isWallMagnetVertical = !isWallMagnetVertical

        arSceneView.configureSession { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        }

        planeGridRenderer.update(isWallMagnetVertical)

        if (isWallMagnetVertical) {
            wallMagnetButton.rotation = -90f
            statusText.text = getString(R.string.status_wall_mode)
        } else {
            wallMagnetButton.rotation = 0f
            statusText.text = getString(R.string.status_floor_mode)
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

            if (isWallMagnetVertical) {
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
            node.setAnimationPlaying(false)

            selectModel(node)

            val profile = profileManager.loadDefault(node.getModeleName())
            if (profile != null) {
                val selected = selectedModel ?: return@launch
                val wrapped   = selected.getWrappedNode() ?: return@launch
                wrapped.scale    = Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
                wrapped.rotation = Float3(profile.rotationX, profile.rotationY, profile.rotationZ)
                selected.syncBaseScale()
                selected.syncBaseRotation()
                selected.refreshRingScale()
                modelControls.resetSlidersToNeutral()
            }
        }
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private fun selectModel(defaultNode: DefaultModelNode) {
        log("SELECT MODEL CALLED")

        selectedModel?.let { currentWrapper ->
            if (isAnimationPlaying) {
                currentWrapper.setAnimationPlaying(false)
            }
            val returnedNode = currentWrapper.unwrap()
            if (returnedNode != null && !models.contains(returnedNode)) {
                log("Adding returning model back to model list")
                models.add(returnedNode)
            }
        }

        isAnimationPlaying = false
        selectedModel?.setAnimationPlaying(false)

        dimensionHud.visibility = View.GONE
        models.remove(defaultNode)

        val wrapped = defaultNode.wrapAsSelected(scope = lifecycleScope)
        selectedModel = wrapped

        animationToggleButton.alpha = 0.5f

        if (wrapped != null) {
            modelControls.bindToNode(wrapped)
            modelControls.visibility      = View.VISIBLE
            wireframeModeButton.visibility = View.VISIBLE
            wireframeModeButton.alpha      = 0.5f

            isRotationRingVisible = true
            rotationRingToggleButton.alpha      = 1.0f
            rotationRingToggleButton.visibility = View.VISIBLE

            if (wrapped.hasAnimations()) {
                animationToggleButton.visibility = View.VISIBLE
                animationToggleButton.alpha      = 0.5f
            } else {
                animationToggleButton.visibility = View.GONE
            }

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
            selectedModel?.setAnimationPlaying(false)
            isAnimationPlaying = false
        }

        val returnedNode = selectedModel?.unwrap()
        if (returnedNode != null && !models.contains(returnedNode)) {
            models.add(returnedNode)
        }
        selectedModel = null
        modelControls.visibility            = View.GONE
        wireframeModeButton.visibility      = View.GONE
        wireframeModeButton.alpha      = 0.5f
        animationToggleButton.visibility    = View.GONE
        rotationRingToggleButton.visibility = View.GONE
        dimensionHud.visibility             = View.GONE
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

        dialog.onProfileSaved = {
            selectedModel?.syncBaseScale()
            selectedModel?.syncBaseRotation()
            modelControls.resetSlidersToNeutral()
            statusText.text = getString(R.string.status_profile_saved)
        }

        dialog.onLoadProfile = { profile ->
            wrapped.scale    = Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
            wrapped.rotation = Float3(profile.rotationX, profile.rotationY, profile.rotationZ)
            selected.syncBaseScale()
            selected.syncBaseRotation()
            selected.refreshRingScale()
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

            val label = picked.modelPath.substringAfterLast('/').substringBeforeLast('.')
            statusText.text = getString(R.string.model_picker_prompt, label)
        }

        modelPickerPopup.show(btnLibrary, activeModelPath)
    }

    // ── Delete model from scene ───────────────────────────────────────────────

    private fun deleteSelectedModel() {
        val selected   = selectedModel ?: return
        val anchorNode = selected.parent as? AnchorNode

        if (isAnimationPlaying) {
            selected.setAnimationPlaying(false)
            isAnimationPlaying = false
        }

        anchorNode?.anchor?.detach()
        anchorNode?.parent = null

        val default   = selected.unwrap()
        val modelName = default?.getModeleName()
        models.remove(default)
        selectedModel = null
        modelControls.visibility            = View.GONE
        wireframeModeButton.visibility      = View.GONE
        animationToggleButton.visibility    = View.GONE
        rotationRingToggleButton.visibility = View.GONE
        dimensionHud.visibility             = View.GONE

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

        if (isAnimationPlaying) {
            selectedModel?.setAnimationPlaying(false)
            isAnimationPlaying = false
        }
        placedModelNodes.forEach { it.anchor?.detach() }
        placedMeasureNodes.forEach { it.anchor?.detach() }
        planeGridRenderer.destroy()
        arSceneView.session?.pause()
        arSceneView.destroy()
        finish()
    }

    override fun onPause() {
        super.onPause()
        arSceneView.session?.pause()
        viewAttachmentManager.onPause()
    }

    override fun onDestroy() {
        placedModelNodes.forEach { it.parent = null }
        placedModelNodes.clear()
        placedMeasureNodes.clear()

        if (!isClosing) {
            planeGridRenderer.destroy()
            arSceneView.session?.pause()
            arSceneView.destroy()
        }

        viewAttachmentManager.onPause()

        super.onDestroy()
    }
}