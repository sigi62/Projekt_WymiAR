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

    private val placedMeasureNodes = mutableListOf<AnchorNode>()
    private val placedModelNodes   = mutableListOf<AnchorNode>()

    private val models         = mutableListOf<DefaultModelNode>()
    private var selectedModel: SelectedModelNode? = null

    private var measurePointA: Float3? = null
    private var measurePointB: Float3? = null

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
        // Keep the native PlaneRenderer enabled — it handles plane geometry
        // correctly. We just swap its "texture" parameter to our grid bitmap.
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
            // Tint the icon so the user knows whether animation is active
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

//        unitButton.setOnClickListener {
//            unit = when (unit) {
//                DistanceUnit.METERS      -> DistanceUnit.CENTIMETERS
//                DistanceUnit.CENTIMETERS -> DistanceUnit.MILLIMETERS
//                DistanceUnit.MILLIMETERS -> DistanceUnit.METERS
//            }
//            settings.distanceUnit = unit   // ← persist
//            unitButton.text = when (unit) {
//                DistanceUnit.METERS      -> getString(R.string.unit_m)
//                DistanceUnit.CENTIMETERS -> getString(R.string.unit_cm)
//                DistanceUnit.MILLIMETERS -> getString(R.string.unit_mm)
//            }
//            measureOverlay.setUnit(unit)
//            modelControls.updateUnit(unit)
//
//            if (measurePointA != null && measurePointB != null) {
//                val dist = distanceMeters(measurePointA!!, measurePointB!!)
//                val (value, suffix) = unit.convert(dist)
//                statusText.text = getString(R.string.measure_distance, value, suffix)
//            }
//
//            if (dimensionHud.visibility == View.VISIBLE) {
//                selectedModel?.getDimensionOverlay()?.let { updateDimensionHud(it.getDimensions()) }
//            }
//        }

//        unitButton.text = when (unit) {
//            DistanceUnit.METERS      -> getString(R.string.unit_m)
//            DistanceUnit.CENTIMETERS -> getString(R.string.unit_cm)
//            DistanceUnit.MILLIMETERS -> getString(R.string.unit_mm)
//        }

        statusText.text = getString(R.string.status_active_model, activeModelPath.substringAfterLast('/').substringBeforeLast('.'))

        arSceneView.onTouchEvent = onTouchEvent@{ motionEvent, hitResult ->
            val x = motionEvent.x
            val y = motionEvent.y

            val nodeHit = hitResult?.node


            // Replace lines 263–275 with this:

            val allowedPlaneTypes: Set<Plane.Type> = if (isWallMagnetVertical)
                setOf(Plane.Type.VERTICAL)
            else
                setOf(Plane.Type.HORIZONTAL_UPWARD_FACING, Plane.Type.HORIZONTAL_DOWNWARD_FACING)

            // Always use a plane hit for model placement and dragging — plane hits
            // carry a well-defined orientation (surface normal) that we use to build
            // a clean upright anchor. DepthPoint hits have an arbitrary orientation
            // and caused models to appear skewed or lying on their side, so they are
            // intentionally excluded here. Measure-point placement (further below)
            // still benefits from depth precision but only uses the position, not
            // the orientation, so it also works fine with plane-only hits.
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
                        // NEW: guard against tapping into an unready scene
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
//            unitButton.text = when (unit) {
//                DistanceUnit.METERS      -> getString(R.string.unit_m)
//                DistanceUnit.CENTIMETERS -> getString(R.string.unit_cm)
//                DistanceUnit.MILLIMETERS -> getString(R.string.unit_mm)
//            }
            measureOverlay.setUnit(unit)
            modelControls.updateUnit(unit)
        }
    }

    // ── Measure tool ─────────────────────────────────────────────────────────

    private fun toggleMeasureTool() {
        isMeasureToolActive = !isMeasureToolActive
        measureModeButton.alpha = if (isMeasureToolActive) 1.0f else 0.5f
        if (!isMeasureToolActive) {
            clearMeasurements()
            statusText.text = getString(R.string.status_active_model,
                activeModelPath.substringAfterLast('/').substringBeforeLast('.'))
        } else {
            statusText.text = getString(R.string.measure_tap_first)
        }
    }

    // ── Wall-magnet mode ──────────────────────────────────────────────────────

    private fun toggleWallMagnetMode() {
        isWallMagnetVertical = !isWallMagnetVertical

        // Always keep HORIZONTAL_AND_VERTICAL so ARCore never stops tracking
        // either plane type. Plane *visibility* is filtered every frame inside
        // PlaneGridRenderer.update(), and hit-test filtering is in the touch
        // handler — no session reconfiguration needed here.
        // (Switching to VERTICAL-only mid-session caused already-detected
        // horizontal planes to flicker or vanish on many devices.)
        arSceneView.configureSession { _, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        }

        // Force an immediate visibility pass so the change feels instant.
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
            // ── Key distinction: asset path vs absolute file path ─────────────

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

            // ── Build an upright anchor ───────────────────────────────────────
            // hitResult.createAnchor() bakes the full hit pose (including surface-
            // normal tilt) into the anchor, which makes the node inherit that tilt
            // and appear skewed — especially on vertical planes and DepthPoint hits.
            // Instead we create an anchor at the same world position but with an
            // identity orientation so the node always starts axis-aligned.
            val hitPose = hitResult.hitPose
            val session = arSceneView.session ?: return@launch
            val uprightPose = Pose.makeTranslation(hitPose.tx(), hitPose.ty(), hitPose.tz())
            val anchor = session.createAnchor(uprightPose)

            val anchorNode = AnchorNode(arSceneView.engine, anchor)
            anchorNode.addChildNode(node)
            arSceneView.addChildNode(anchorNode)

            // ── Wall-magnet: face the model outward from the wall ─────────────
            // The hit pose's X-axis is the wall's horizontal "right" direction and
            // its Z-axis points outward (the surface normal). We extract the yaw
            // (rotation around world-Y) from that normal so the model faces out.
            if (isWallMagnetVertical) {
                // Wall normal in world space = hit pose's +Z column
                // quat → rotation matrix column 2 = (2(qx*qz+qy*qw),
                //                                   2(qy*qz-qx*qw),
                //                                   1-2(qx²+qy²))
                val qx = hitPose.qx(); val qy = hitPose.qy()
                val qz = hitPose.qz(); val qw = hitPose.qw()
                val normalX =  2f * (qx * qz + qy * qw)
                val normalZ =  1f - 2f * (qx * qx + qy * qy)
                // atan2 gives the yaw angle that makes the model face the normal
                val yawDeg = Math.toDegrees(atan2(normalX.toDouble(), normalZ.toDouble())).toFloat()
                node.rotation = Float3(0f, yawDeg, 0f)
                log("Wall placement: normal=($normalX, $normalZ) → yaw=$yawDeg°")
            }

            placedModelNodes.add(anchorNode)

            log("Adding new model to models list")
            models.add(node)
            node.setAnimationPlaying(false)

            selectModel(node)

// Apply default profile AFTER selectModel() so the SelectedModelNode wrapper
// exists, baseScale is Float3(1f), and bindToNode seeds sliders correctly.
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

        // Stop animation on the previously selected model before switching
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

        // Reset animation state for the newly selected model
        isAnimationPlaying = false
        selectedModel?.setAnimationPlaying(false)

        dimensionHud.visibility = View.GONE
        models.remove(defaultNode)

        val wrapped = defaultNode.wrapAsSelected(scope = lifecycleScope)
        selectedModel = wrapped

        animationToggleButton.alpha = 0.5f

        if (wrapped != null) {
            // bindToNode seeds sliders from the node's actual scale/rotation,
            // so a model placed with a default profile shows the right values.
            modelControls.bindToNode(wrapped)
            modelControls.visibility      = View.VISIBLE
            wireframeModeButton.visibility = View.VISIBLE
            wireframeModeButton.alpha      = 0.5f

            // ── Rotation ring: show and reset to visible state ────────────────
            isRotationRingVisible = true
            rotationRingToggleButton.alpha      = 1.0f
            rotationRingToggleButton.visibility = View.VISIBLE

            // ── Animation button: show only if this model has animations ──────
            if (wrapped.hasAnimations()) {
                animationToggleButton.visibility = View.VISIBLE
                animationToggleButton.alpha      = 0.5f  // dim = stopped
            } else {
                animationToggleButton.visibility = View.GONE
            }

            wireframeModeButton.setOnClickListener {
                val isNowVisible = wrapped.toggleDimensions(arSceneView, viewAttachmentManager)
                wireframeModeButton.alpha = if (isNowVisible) 1.0f else 0.5f   // ← add
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
        // Stop any running animation when deselecting
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

    // ── Measure points ────────────────────────────────────────────────────────
    private val measureAnchorA: AnchorNode? get() = placedMeasureNodes.getOrNull(placedMeasureNodes.size - (if (measurePointB == null) 1 else 2))
    private val measureAnchorB: AnchorNode? get() = if (placedMeasureNodes.size >= 2) placedMeasureNodes.last() else null

    private fun placeMeasurePoint(hitResult: HitResult) {
        val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
        arSceneView.addChildNode(anchorNode)
        placedMeasureNodes.add(anchorNode)

        if (measurePointA == null || measurePointB != null) {
            measurePointA = anchorNode.worldPosition.let { Float3(it.x, it.y, it.z) }
            measurePointB = null
            measureOverlay.setAnchorNodes(anchorNode, null)   // see MeasureTapeOverlayView change below
            statusText.text = getString(R.string.measure_tap_second)
            return
        }

        measurePointB = anchorNode.worldPosition.let { Float3(it.x, it.y, it.z) }
        measureOverlay.setAnchorNodes(placedMeasureNodes[placedMeasureNodes.size - 2], anchorNode)

        val dist = distanceMeters(measurePointA!!, measurePointB!!)
        val (value, suffix) = unit.convert(dist)
        statusText.text = getString(R.string.measure_distance, value, suffix)
    }

    private fun clearMeasurements() {
        measurePointA = null
        measurePointB = null
        measureOverlay.clear()

        placedMeasureNodes.forEach {
            it.anchor?.detach()
            it.parent = null
        }
        placedMeasureNodes.clear()
    }

    // ── Profile dialog ────────────────────────────────────────────────────────

    private fun showProfileDialog() {
        val selected  = selectedModel ?: return
        val wrapped   = selected.getWrappedNode() ?: return
        val modelName = wrapped.getModeleName()

        val dialog = ProfilePickerDialog.newInstance(modelName)

        dialog.getCurrentProfile = {
            // Snapshot the node's current physical scale and rotation.
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
            // Apply the profile to the node, then rebind so sliders seed from
            // the node's new state.
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
        // We just set the listener and tell the popup to show itself
        modelPickerPopup.onModelPicked = { picked ->
            activeModelPath = picked.modelPath
            activeModelIsAsset = picked.isAsset

            val label = picked.modelPath.substringAfterLast('/').substringBeforeLast('.')
            statusText.text = getString(R.string.model_picker_prompt, label)
        }

        // Highlighting logic is handled inside based on activeModelPath
        modelPickerPopup.show(btnLibrary, activeModelPath)
    }

    // ── Delete model from scene ───────────────────────────────────────────────

    private fun deleteSelectedModel() {
        val selected   = selectedModel ?: return
        val anchorNode = selected.parent as? AnchorNode

        // Stop animation before destroying the node
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

        // Stop any running animation
        if (isAnimationPlaying) {
            selectedModel?.setAnimationPlaying(false)
            isAnimationPlaying = false
        }
        // Detach all anchors so ARCore releases tracking resources
        placedModelNodes.forEach { it.anchor?.detach() }
        placedMeasureNodes.forEach { it.anchor?.detach() }
        // Destroy custom plane renderer before tearing down the scene
        planeGridRenderer.destroy()
        // Pause and destroy the AR session before leaving
        arSceneView.session?.pause()
        arSceneView.destroy()
        finish()
    }

    override fun onPause() {
        super.onPause()
        // This is the standard way to ensure the AR session pauses
        arSceneView.session?.pause()
        viewAttachmentManager.onPause()
    }

    override fun onDestroy() {
        // 1. Clear all nodes to release Sceneform/Sceneview references
        placedModelNodes.forEach { it.parent = null }
        placedModelNodes.clear()
        placedMeasureNodes.clear()

        // 2. Only destroy the session if closeScene() hasn't already done it
        if (!isClosing) {
            planeGridRenderer.destroy()
            arSceneView.session?.pause()
            arSceneView.destroy()
        }

        // 3. Clean up the ViewAttachmentManager
        viewAttachmentManager.onPause()

        super.onDestroy()
    }
}