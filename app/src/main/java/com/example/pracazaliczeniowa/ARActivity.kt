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
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import android.util.Log

import com.example.pracazaliczeniowa.Nodes.DefaultModelNode
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode

import com.example.pracazaliczeniowa.Overlays.DistanceUnit
import com.example.pracazaliczeniowa.Overlays.MeasureTapeOverlayView
import com.example.pracazaliczeniowa.Overlays.ModelControlOverlayView
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import io.github.sceneview.node.Node


fun log(msg: String) {
    Log.d("AR_DEBUG", msg)
}

class ARActivity : AppCompatActivity() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPlacement: Runnable? = null

    private lateinit var arSceneView: ARSceneView
    private lateinit var viewAttachmentManager: ViewAttachmentManager
    private lateinit var statusText: TextView
    private lateinit var modelControls: ModelControlOverlayView
    private lateinit var measureOverlay: MeasureTapeOverlayView
    private lateinit var profileManager: ProfileManager
    private lateinit var measureModeButton: ImageButton
    private lateinit var wireframeModeButton: ImageButton
    private lateinit var unitButton: Button
    private lateinit var settingsButton: ImageButton   // ← NEW

    private var isMeasureToolActive: Boolean = false
    private var unit: DistanceUnit = DistanceUnit.CENTIMETERS

    private val placedMeasureNodes = mutableListOf<AnchorNode>()
    private val placedModelNodes = mutableListOf<AnchorNode>()

    private val models = mutableListOf<DefaultModelNode>()
    private var selectedModel: SelectedModelNode? = null

    private var measurePointA: Float3? = null
    private var measurePointB: Float3? = null

    private var isDragging = false
    private var isPinching = false
    private var isRotating = false

    private var initialPinchDistance = 0f
    private var touchStartPos = android.graphics.PointF()
    private val MOVE_THRESHOLD = 20f
    private var initialTouchAngle = 0f
    private var initialModelRotationY = 0f

    // ---------------------------------------------------------------
    // Active model path – set from LibraryActivity via Intent extra.
    // Falls back to cat.glb so the app still works if launched directly.
    // ---------------------------------------------------------------
    private var activeModelPath: String = "models/cat.glb"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        // Read the model path chosen in LibraryActivity
        activeModelPath = intent.getStringExtra(LibraryActivity.EXTRA_MODEL_PATH)
            ?: "models/cat.glb"

        profileManager = ProfileManager(this)

        arSceneView           = findViewById(R.id.arSceneView)
        statusText            = findViewById(R.id.statusText)
        modelControls         = findViewById(R.id.modelControls)
        measureOverlay        = findViewById(R.id.measureOverlay)
        measureModeButton     = findViewById(R.id.btnMeasureTapeModeToggle)
        wireframeModeButton   = findViewById(R.id.btnWireframeToggle)
        unitButton            = findViewById(R.id.btnUnit)
        settingsButton        = findViewById(R.id.btnSettings)       // ← NEW

        // ── Apply saved overlay defaults before any node is bound ───────
        modelControls.applySettings(AppSettings(this))

        // ── Settings button ─────────────────────────────────────────────
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        viewAttachmentManager = ViewAttachmentManager(this, arSceneView)
        arSceneView.lifecycle = lifecycle

        lifecycleScope.launch {
            val env = arSceneView.environmentLoader.loadHDREnvironment("environment.hdr")
            arSceneView.indirectLight = env?.indirectLight
            arSceneView.skybox        = env?.skybox
        }

        modelControls.onSaveRequested = {
            showProfileDialog()
        }
        modelControls.onDeleteRequested = {
            deleteSelectedModel()
        }

        modelControls.visibility     = View.GONE
        wireframeModeButton.visibility = View.GONE

        measureOverlay.attach(arSceneView)
        measureOverlay.setUnit(unit)

        measureModeButton.setOnClickListener {
            toggleMeasureTool()
        }

        unitButton.setOnClickListener {
            unit = when (unit) {
                DistanceUnit.METERS      -> DistanceUnit.CENTIMETERS
                DistanceUnit.CENTIMETERS -> DistanceUnit.MILLIMETERS
                DistanceUnit.MILLIMETERS -> DistanceUnit.METERS
            }

            unitButton.text = when (unit) {
                DistanceUnit.METERS      -> "m"
                DistanceUnit.CENTIMETERS -> "cm"
                DistanceUnit.MILLIMETERS -> "mm"
            }

            measureOverlay.setUnit(unit)
            modelControls.updateUnit(unit)

            if (measurePointA != null && measurePointB != null) {
                val dist = distanceMeters(measurePointA!!, measurePointB!!)
                val (value, suffix) = unit.convert(dist)
                statusText.text = String.format("Distance: %.1f %s", value, suffix)
            }
        }

        // Show which model is active
        statusText.text = "Active: ${activeModelPath.substringAfterLast('/').substringBeforeLast('.')}"

        arSceneView.onTouchEvent = onTouchEvent@{ motionEvent, hitResult ->
            val x = motionEvent.x
            val y = motionEvent.y

            val nodeHit = hitResult?.node
            val arHit = arSceneView.hitTestAR(x, y, setOf(Plane.Type.HORIZONTAL_UPWARD_FACING))
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
                        if (arHit != null) placeMeasurePoint(arHit)
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

    // ---------------------------------------------------------------
    // Re-apply settings when returning from SettingsActivity so that
    // the next model placement picks up any changed defaults.
    // ---------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        viewAttachmentManager.onResume()
        modelControls.applySettings(AppSettings(this))
    }

    // ---------------------------------------------------------------
    // Measure tool toggle
    // ---------------------------------------------------------------
    private fun toggleMeasureTool() {
        isMeasureToolActive = !isMeasureToolActive
        if (!isMeasureToolActive) {
            clearMeasurements()
            statusText.text = "Active: ${activeModelPath.substringAfterLast('/').substringBeforeLast('.')}"
        } else {
            statusText.text = "Tap to place first measure point"
        }
    }

    // ---------------------------------------------------------------
    // Model placement
    // ---------------------------------------------------------------
    private fun placeModel(hitResult: HitResult) {
        statusText.text = "Loading model…"

        lifecycleScope.launch {
            val modelInstance = arSceneView.modelLoader.createModelInstance(activeModelPath)

            val node = DefaultModelNode(
                modelPath             = activeModelPath,
                modelInstance         = modelInstance,
                scope                 = lifecycleScope,
                sceneView             = arSceneView,
                viewAttachmentManager = viewAttachmentManager
            )

            // Auto-apply the default profile if one has been saved
            val profile = profileManager.loadDefault(node.getModeleName())
            if (profile != null) {
                node.scale    = Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
                node.rotation = Float3(profile.rotationX, profile.rotationY, profile.rotationZ)
            }

            val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
            anchorNode.addChildNode(node)
            arSceneView.addChildNode(anchorNode)

            placedModelNodes.add(anchorNode)

            log("Adding new model to models list")
            models.add(node)

            selectModel(node)
        }
    }

    private fun selectModel(defaultNode: DefaultModelNode) {
        log("SELECT MODEL CALLED")

        selectedModel?.let { currentWrapper ->
            val returnedNode = currentWrapper.unwrap()
            if (returnedNode != null && !models.contains(returnedNode)) {
                log("Adding returning model back to model list")
                models.add(returnedNode)
            }
        }

        models.remove(defaultNode)

        val wrapped = defaultNode.wrapAsSelected(scope = lifecycleScope)
        selectedModel = wrapped

        if (wrapped != null) {
            modelControls.bindToNode(wrapped)
            modelControls.visibility      = View.VISIBLE
            wireframeModeButton.visibility = View.VISIBLE

            wireframeModeButton.setOnClickListener {
                wrapped.toggleDimensions(arSceneView, viewAttachmentManager)
                statusText.text = "Toggled dimensions"
            }
        }

        statusText.text = "Model selected"
    }

    private fun deselectModel() {
        val returnedNode = selectedModel?.unwrap()
        if (returnedNode != null && !models.contains(returnedNode)) {
            models.add(returnedNode)
        }
        selectedModel = null
        modelControls.visibility      = View.GONE
        wireframeModeButton.visibility = View.GONE
        statusText.text = "Model deselected"
    }

    private fun placeMeasurePoint(hitResult: HitResult) {
        val pose  = hitResult.hitPose
        val point = Float3(pose.tx(), pose.ty(), pose.tz())

        val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
        arSceneView.addChildNode(anchorNode)
        placedMeasureNodes.add(anchorNode)

        if (measurePointA == null || measurePointB != null) {
            measurePointA = point
            measurePointB = null
            measureOverlay.setPoints(measurePointA, null)
            statusText.text = "Tap the second point"
            return
        }

        measurePointB = point
        measureOverlay.setPoints(measurePointA, measurePointB)

        val dist = distanceMeters(measurePointA!!, measurePointB!!)
        val (value, suffix) = unit.convert(dist)
        statusText.text = String.format("Distance: %.1f %s", value, suffix)
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

    /** Opens the profile picker dialog for the currently selected model. */
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
                rotationX = wrapped.rotation.x,
                rotationY = wrapped.rotation.y,
                rotationZ = wrapped.rotation.z
            )
        }

        dialog.onLoadProfile = { profile ->
            selected.scale    = Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
            selected.rotation = Float3(profile.rotationX, profile.rotationY, profile.rotationZ)
            modelControls.bindToNode(selected)
        }

        dialog.onStatusUpdate = { message -> statusText.text = message }

        dialog.show(supportFragmentManager, "ProfilePickerDialog")
    }

    private fun deleteSelectedModel() {
        val selected   = selectedModel ?: return
        val anchorNode = selected.parent as? AnchorNode

        anchorNode?.anchor?.detach()
        anchorNode?.parent = null

        val default   = selected.unwrap()
        val modelName = default?.getModeleName()
        models.remove(default)
        selectedModel = null
        modelControls.visibility      = View.GONE
        wireframeModeButton.visibility = View.GONE

        statusText.text = "deleted model $modelName"
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------
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

    override fun onPause() {
        super.onPause()
        viewAttachmentManager.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSceneView.destroy()
    }
}
