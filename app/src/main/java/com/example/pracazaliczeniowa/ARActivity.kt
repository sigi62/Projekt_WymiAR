
package com.example.pracazaliczeniowa

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
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
import android.widget.ImageButton

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

    private var isMeasureToolActive: Boolean = false
    private var unit: DistanceUnit = DistanceUnit.CENTIMETERS

    private val placedMeasureNodes = mutableListOf<AnchorNode>()
    private val placedModelNodes = mutableListOf<AnchorNode>()

    // ✅ Clean model system
    private val models = mutableListOf<DefaultModelNode>()
    private var selectedModel: SelectedModelNode? = null


    private var measurePointA: Float3? = null
    private var measurePointB: Float3? = null

    // Add these flags to ARActivity
    private var isDragging = false
    private var isPinching = false
    private var isRotating = false

    private var initialPinchDistance = 0f
    private var touchStartPos = android.graphics.PointF()
    private val MOVE_THRESHOLD = 20f // Pixels; ignore taps if the finger moved more than this
    private var initialTouchAngle = 0f
    private var initialModelRotationY = 0f


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        profileManager = ProfileManager(this)

        arSceneView = findViewById(R.id.arSceneView)
        statusText = findViewById(R.id.statusText)
        modelControls = findViewById(R.id.modelControls)
        measureOverlay = findViewById(R.id.measureOverlay)
        measureModeButton = findViewById(R.id.btnMeasureTapeModeToggle)
        wireframeModeButton = findViewById(R.id.btnWireframeToggle)
        unitButton = findViewById(R.id.btnUnit)


        viewAttachmentManager = ViewAttachmentManager(this, arSceneView)
        arSceneView.lifecycle = lifecycle

        lifecycleScope.launch {
            val env = arSceneView.environmentLoader.loadHDREnvironment("environment.hdr")
            arSceneView.indirectLight = env?.indirectLight
            arSceneView.skybox = env?.skybox
        }

        modelControls.onSaveRequested = {
            saveCurrentModelProfile()
        }
        modelControls.onDeleteRequested = {
            deleteSelectedModel()
        }

        modelControls.visibility = View.GONE
        wireframeModeButton.visibility = View.GONE

        measureOverlay.attach(arSceneView)
        measureOverlay.setUnit(unit)


        measureModeButton.setOnClickListener {
            toggleMeasureTool()
        }

        unitButton.setOnClickListener {
            unit = when (unit) {
                DistanceUnit.METERS -> DistanceUnit.CENTIMETERS
                DistanceUnit.CENTIMETERS -> DistanceUnit.MILLIMETERS
                DistanceUnit.MILLIMETERS -> DistanceUnit.METERS
            }

            unitButton.text = when (unit) {
                DistanceUnit.METERS -> "m"
                DistanceUnit.CENTIMETERS -> "cm"
                DistanceUnit.MILLIMETERS -> "mm"
            }

            measureOverlay.setUnit(unit)

            if (measurePointA != null && measurePointB != null) {
                val dist = distanceMeters(measurePointA!!, measurePointB!!)
                val (value, suffix) = unit.convert(dist)
                statusText.text = String.format("Distance: %.1f %s", value, suffix)
            }
        }

        arSceneView.onTouchEvent = onTouchEvent@{ motionEvent, hitResult ->
            val x = motionEvent.x
            val y = motionEvent.y

            // 1. Identify what was touched: A virtual node vs the real-world floor
            val nodeHit = hitResult?.node
            val arHit = arSceneView.hitTestAR(x, y, setOf(Plane.Type.HORIZONTAL_UPWARD_FACING))
            val selected = selectedModel

            // --- GESTURE PHASE: Dragging & Pinching ---
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartPos.set(x, y)

                    if (nodeHit?.name == "rotation_handle") {
                        isRotating = true
                        touchStartPos.set(x, y) // Capture the exact pixel where the touch started
                        initialModelRotationY = selected!!.rotation.y // Store the starting rotation
                        isDragging = false
                    }
                    else {
                        // If we hit the model or floor, handle dragging
                        isRotating = false
                        isDragging = (selected != null && (nodeHit == selected || nodeHit?.parent == selected || nodeHit == selected.getWrappedNode()))
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (motionEvent.pointerCount == 2 && selected != null) {
                        isPinching = true
                        isDragging = false // Pinching overrides dragging
                        initialPinchDistance = getFingerSpacing(motionEvent)
                        selected.startPinching()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // One finger lifted: Stop pinching immediately to prevent index errors
                    isPinching = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isRotating && selected != null) {
                        val dx = motionEvent.x - touchStartPos.x // How many pixels did the finger move?

                        // 1 pixel = 0.5 degrees (Adjust this for speed!)
                        // Higher multiplier = Faster rotation
                        val sensitivity = 0.2f
                        val rotationOffset = dx * sensitivity

                        val newRotationY = initialModelRotationY + rotationOffset // Subtract to match finger direction

                        // Apply the rotation
                        selected.rotation = Float3(0f, newRotationY, 0f)

                        // Sync the UI sliders
                        modelControls.updateRotationFromHandle(newRotationY)
                        return@onTouchEvent true
                    }

                    if (isPinching &&  motionEvent.pointerCount >= 2 && selected != null) {
                        val currentDist = getFingerSpacing(motionEvent)
                        if (currentDist > 10f) {
                            val scaleFactor = currentDist / initialPinchDistance
                            selected.applyPinchScale(scaleFactor)
                            modelControls.updateScaleFromGesture(scaleFactor)
                        }
                        return@onTouchEvent true
                    }

                    if (isDragging && selected != null && arHit != null) {
                        // Use the REAL-WORLD surface hit to move the model
                        selected.moveTo(Float3(arHit.hitPose.tx(), arHit.hitPose.ty(), arHit.hitPose.tz()))
                        return@onTouchEvent true
                    }

                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = x - touchStartPos.x
                    val dy = y - touchStartPos.y
                    val distanceMoved = sqrt(dx * dx + dy * dy)

                    // If it was a movement/gesture, don't trigger a tap action
                    val wasGesture = isDragging || isPinching || distanceMoved > MOVE_THRESHOLD
                    isDragging = false
                    isPinching = false

                    if (wasGesture) return@onTouchEvent true

                    // --- TAP LOGIC: Use the AR Hit for placement/points ---
                    if (arHit != null) {
                        handleTap(arHit, nodeHit)
                    }
                }
            }

            true
        }
    }

    private fun handleTap(arHit: HitResult, nodeHit: Node?) {
        // 1. Priority: Measurement Tool
        if (isMeasureToolActive) {
            placeMeasurePoint(arHit)
            return
        }

        // 2. Priority: Selection (Is it a model or a child of a model?)
        val modelNode = nodeHit as? DefaultModelNode ?: nodeHit?.parent as? DefaultModelNode
        val selected = selectedModel

        when {
            // CASE: Tapping the already selected model (or its wrapper)
            selected != null && (nodeHit == selected || modelNode == selected.getWrappedNode()) -> {
                // Optional: You could deselect here to toggle, or just show status
                deselectModel()
            }

            // CASE: Tapping a DIFFERENT model
            modelNode != null -> {
                selectModel(modelNode)
            }

            // CASE: Tapping empty floor while a model is selected -> Deselect
            selected != null -> {
                deselectModel()
            }

            // CASE: Tapping empty floor with nothing selected -> Place new
            else -> {
                placeModel(arHit)
            }
        }
    }


    private fun toggleMeasureTool() {
        isMeasureToolActive = !isMeasureToolActive

        if (isMeasureToolActive) {
            measureOverlay.visibility = View.VISIBLE
            statusText.text = "Tap 2 points to measure"
        } else {
            measureOverlay.visibility = View.GONE
            clearMeasurements() // Remove points when tool is toggled off
            statusText.text = "Tap to place or select a model"
        }
    }


    private fun placeModel(hitResult: HitResult) {
        val modelPath = "models/cat.glb"
        statusText.text = "Loading model…"

        lifecycleScope.launch {

            val modelInstance =
                arSceneView.modelLoader.createModelInstance(modelPath)

            val node = DefaultModelNode(
                modelPath = modelPath,
                modelInstance = modelInstance,
                scope = lifecycleScope,
                sceneView = arSceneView,
                viewAttachmentManager = viewAttachmentManager
            )

            val profile = profileManager.loadProfile(node.getModeleName())
            if (profile != null) {
                node.scale = Float3(profile.scaleX, profile.scaleY, profile.scaleZ)
                node.rotation = Float3(profile.rotationX, profile.rotationY, profile.rotationZ)
            } else {
                node.scaleToUnits(0.02f)
            }

            val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
            anchorNode.addChildNode(node)
            arSceneView.addChildNode(anchorNode)

            placedModelNodes.add(anchorNode)

            log("Adding new model to models list)")
            models.add(node)

            selectModel(node)

        }
    }

    private fun selectModel(defaultNode: DefaultModelNode) {

        log("SELECT MODEL CALLED")

        selectedModel?.let { currentWrapper ->
            val returnedNode = currentWrapper.unwrap()
            if (returnedNode != null) {
                // Ensure the old model is back in the general list
                if (!models.contains(returnedNode)) {

                    log("Adding returning model back to model list)")
                    models.add(returnedNode)
                }
            }
        }

        // 2. Remove the new target from the general list
        models.remove(defaultNode)

        val wrapped = defaultNode.wrapAsSelected(
            scope = lifecycleScope
        )

        selectedModel = wrapped

        if (wrapped != null) {
            modelControls.bindToNode(wrapped)
            modelControls.visibility = View.VISIBLE
            wireframeModeButton.visibility = View.VISIBLE

            wireframeModeButton.setOnClickListener {
                // ✅ The node handles its own logic now
                wrapped.toggleDimensions(arSceneView, viewAttachmentManager)

                // Optional: Feedback based on the new state
                statusText.text = "Toggled dimensions"
            }
        }

        //dimensionOverlay.attach(arSceneView, wrapped)
        //modelControls.bindToNode(wrapped)

        statusText.text = "Model selected"
    }

    private fun deselectModel() {
        val returnedNode = selectedModel?.unwrap()
        if (returnedNode != null && !models.contains(returnedNode)) {
            models.add(returnedNode)
        }
        selectedModel = null
        modelControls.visibility = View.GONE
        wireframeModeButton.visibility = View.GONE
        statusText.text = "Model deselected"
    }

    // Update placeMeasurePoint to use the measureNodes list
    private fun placeMeasurePoint(hitResult: HitResult) {
        val pose = hitResult.hitPose
        val point = Float3(pose.tx(), pose.ty(), pose.tz())

        val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
        arSceneView.addChildNode(anchorNode)
        placedMeasureNodes.add(anchorNode) // Track specifically as a measure node

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

        // Remove only measurement anchors from the scene
        placedMeasureNodes.forEach {
            it.anchor?.detach()
            it.parent = null
        }
        placedMeasureNodes.clear()
    }

    private fun saveCurrentModelProfile() {
        val selected = selectedModel ?: return
        val wrapped = selected.getWrappedNode() ?: return

        val profileName = wrapped.getModeleName()
        // Note: You might want to pass the actual model name dynamically
        val profile = ModelProfile(
            scaleX = wrapped.scale.x,
            scaleY = wrapped.scale.y,
            scaleZ = wrapped.scale.z,
            rotationX = wrapped.rotation.x,
            rotationY = wrapped.rotation.y,
            rotationZ = wrapped.rotation.z
        )

        profileManager.saveProfile(profileName, profile)
        statusText.text = "Saved default for $profileName"
    }

    private fun deleteSelectedModel() {
        val selected = selectedModel ?: return

        // 1. Get the parent AnchorNode (which holds the model in the AR world)
        val anchorNode = selected.parent as? AnchorNode

        // 2. Detach the anchor from the ARCore Session
        anchorNode?.anchor?.detach()

        // 3. Remove the node from the SceneView hierarchy
        anchorNode?.parent = null

        val default = selected.unwrap()
        val modelName = default?.getModeleName()
        models.remove(default)
        selectedModel = null
        modelControls.visibility = View.GONE
        wireframeModeButton.visibility = View.GONE

        statusText.text = "deleted model $modelName"
    }

    //Helper Functions
    private fun distanceMeters(a: Float3, b: Float3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun getFingerSpacing(event: MotionEvent): Float {
        // Safety check: if there aren't at least 2 fingers, return a neutral value
        if (event.pointerCount < 2) return 10f

        return try {
            val x = event.getX(0) - event.getX(1)
            val y = event.getY(0) - event.getY(1)
            sqrt(x * x + y * y)
        } catch (e: IllegalArgumentException) {
            // Fallback if a pointer disappears during the calculation
            10f
        }
    }
    private fun calculateAngle(touchX: Float, touchY: Float, node: Node): Float {
        // Project the model's 3D position to 2D screen coordinates
        // 1. Get the 3D world position of the node center
        val worldPos = node.worldPosition

        // 2. Project 3D world position to 2D screen coordinates (Pixels)
        // In SceneView 2.3.3, this is accessed via the camera
        val screenPos = arSceneView.cameraNode.worldToView(worldPos)

        // 3. Calculate delta between touch and model center
        val dx = touchX - screenPos.x
        val dy = touchY - screenPos.y

        // 4. atan2 returns the angle in radians; we convert to degrees
        // We use negative dy because screen coordinates (y-down) are inverted compared to math planes
        return Math.toDegrees(Math.atan2((-dy).toDouble(), dx.toDouble())).toFloat()
    }

    override fun onResume() {
        super.onResume()
        viewAttachmentManager.onResume()
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


