
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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        profileManager = ProfileManager(this)

        arSceneView      = findViewById(R.id.arSceneView)
        statusText       = findViewById(R.id.statusText)
        modelControls    = findViewById(R.id.modelControls)
        measureOverlay   = findViewById(R.id.measureOverlay)
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

            if (measurePointA != null && measurePointB != null) {
                val dist = distanceMeters(measurePointA!!, measurePointB!!)
                val (value, suffix) = unit.convert(dist)
                statusText.text = String.format("Distance: %.1f %s", value, suffix)
            }
        }

        arSceneView.onTouchEvent = onTouchEvent@{ motionEvent, hitResult ->

            if (motionEvent.action == MotionEvent.ACTION_UP) {

                pendingPlacement?.let { handler.removeCallbacks(it) }

                if (isMeasureToolActive) {
                    val task = Runnable {
                        val hit = arSceneView.hitTestAR(
                            xPx = motionEvent.x,
                            yPx = motionEvent.y,
                            planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                            trackingStates = setOf(TrackingState.TRACKING),
                        )
                        hit?.let { placeMeasurePoint(it) }
                    }
                    pendingPlacement = task
                    handler.postDelayed(task, 150)
                    return@onTouchEvent true
                }

                val tappedNode = hitResult?.node

                // 1. Check if we tapped an existing model or its wrapper
                if (tappedNode is SelectedModelNode) {
                    statusText.text = "Model already selected"
                    return@onTouchEvent true
                }

                val modelNode = tappedNode as? DefaultModelNode ?: tappedNode?.parent as? DefaultModelNode

                if (modelNode != null) {
                    selectModel(modelNode)
                    return@onTouchEvent true
                } else if (selectedModel != null) {
                    deselectModel()
                    return@onTouchEvent true
                }

                val task = Runnable {
                    val hit = arSceneView.hitTestAR(
                        xPx = motionEvent.x,
                        yPx = motionEvent.y,
                        planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                        trackingStates = setOf(TrackingState.TRACKING),
                    )
                    hit?.let { placeModel(it) }
                }

                pendingPlacement = task
                handler.postDelayed(task, 150)
            }

            true
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

        statusText.text = "deleted model $modelName"
    }

    //Helper Functions
    private fun distanceMeters(a: Float3, b: Float3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
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


