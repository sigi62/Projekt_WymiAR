
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

import com.example.pracazaliczeniowa.Nodes.DefaultModelNode
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode

import com.example.pracazaliczeniowa.Overlays.DimensionOverlayView
import com.example.pracazaliczeniowa.Overlays.DistanceUnit
import com.example.pracazaliczeniowa.Overlays.MeasureTapeOverlayView
import com.example.pracazaliczeniowa.Overlays.ModelControlOverlayView


fun log(msg: String) {
    Log.d("AR_DEBUG", msg)
}

class ARActivity : AppCompatActivity() {

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPlacement: Runnable? = null

    private lateinit var arSceneView: ARSceneView
    private lateinit var statusText: TextView
    private lateinit var dimensionOverlay: DimensionOverlayView
    private lateinit var modelControls: ModelControlOverlayView
    private lateinit var measureOverlay: MeasureTapeOverlayView
    private lateinit var measureModeButton: Button
    private lateinit var unitButton: Button

    private var mode: Mode = Mode.MODEL
    private var unit: DistanceUnit = DistanceUnit.METERS

    private val placedAnchors = mutableListOf<AnchorNode>()

    // ✅ Clean model system
    private val models = mutableListOf<DefaultModelNode>()
    private var selectedModel: SelectedModelNode? = null


    private var measurePointA: Float3? = null
    private var measurePointB: Float3? = null

    private enum class Mode { MODEL, MEASURE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        arSceneView      = findViewById(R.id.arSceneView)
        statusText       = findViewById(R.id.statusText)
        dimensionOverlay = findViewById(R.id.dimensionOverlay)
        modelControls    = findViewById(R.id.modelControls)
        measureOverlay   = findViewById(R.id.measureOverlay)
        measureModeButton = findViewById(R.id.measureModeButton)
        unitButton       = findViewById(R.id.unitButton)

        arSceneView.lifecycle = lifecycle

        lifecycleScope.launch {
            val env = arSceneView.environmentLoader.loadHDREnvironment("environment.hdr")
            arSceneView.indirectLight = env?.indirectLight
            arSceneView.skybox = env?.skybox
        }

        measureOverlay.attach(arSceneView)
        measureOverlay.setUnit(unit)

        applyModeUi()

        measureModeButton.setOnClickListener {
            mode = if (mode == Mode.MODEL) Mode.MEASURE else Mode.MODEL
            resetScene()
            applyModeUi()
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
                log("SCENE TAP at (${motionEvent.x}, ${motionEvent.y})")

                pendingPlacement?.let { handler.removeCallbacks(it) }

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
                    // DESELECT: User tapped empty space, unwrap current selection
                    val returnedNode = selectedModel?.unwrap()
                    if (returnedNode != null && !models.contains(returnedNode)) {
                        models.add(returnedNode)
                    }
                    selectedModel = null
                    statusText.text = "Model deselected"
                    return@onTouchEvent true
                }

                val task = Runnable {
                    when (mode) {

                        Mode.MODEL -> {
                            val hit = arSceneView.hitTestAR(
                                xPx = motionEvent.x,
                                yPx = motionEvent.y,
                                planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                                trackingStates = setOf(TrackingState.TRACKING),
                            )
                            log("Placing new model at (${motionEvent.x}, ${motionEvent.y})")
                            hit?.let { placeModel(it) }
                        }

                        Mode.MEASURE -> {
                            val hit = arSceneView.hitTestAR(
                                xPx = motionEvent.x,
                                yPx = motionEvent.y,
                                planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                                trackingStates = setOf(TrackingState.TRACKING),
                            )
                            if (hit != null) placeMeasurePoint(hit)
                        }
                    }
                }

                pendingPlacement = task
                handler.postDelayed(task, 150)
            }

            true
        }
    }

    private fun applyModeUi() {
        when (mode) {
            Mode.MODEL -> {
                measureModeButton.text = "Measure"
                measureOverlay.visibility = View.GONE
                dimensionOverlay.visibility = View.VISIBLE
                modelControls.visibility = View.VISIBLE
                statusText.text = "Tap to place or select a model"
            }
            Mode.MEASURE -> {
                measureModeButton.text = "Model"
                measureOverlay.visibility = View.VISIBLE
                dimensionOverlay.visibility = View.GONE
                modelControls.visibility = View.GONE
                statusText.text = "Tap 2 points to measure distance"
            }
        }
    }

    private fun resetScene() {

        measurePointA = null
        measurePointB = null
        measureOverlay.clear()

//        selectedModel = null
//        models.clear()
//
//        placedAnchors.forEach {
//            it.anchor.detach()
//            it.parent = null
//        }
//        placedAnchors.clear()
    }

    private fun placeModel(hitResult: HitResult) {

        statusText.text = "Loading model…"

        lifecycleScope.launch {

            val modelInstance =
                arSceneView.modelLoader.createModelInstance("models/cat.glb")

            val node = DefaultModelNode(
                modelInstance = modelInstance!!,
                scope = lifecycleScope
            )

            node.scaleToUnits(0.02f)

            val anchorNode = AnchorNode(arSceneView.engine, hitResult.createAnchor())
            anchorNode.addChildNode(node)
            arSceneView.addChildNode(anchorNode)

            placedAnchors.add(anchorNode)

            log("Adding new model to models list)")
            models.add(node)

//            selectModel(node)
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
            engine = arSceneView.engine,
            scope = lifecycleScope
        )

        selectedModel = wrapped

        //dimensionOverlay.attach(arSceneView, wrapped)
        //modelControls.bindToNode(wrapped)

        statusText.text = "Model selected"
    }

    private fun placeMeasurePoint(hitResult: HitResult) {
        val pose = hitResult.hitPose
        val point = Float3(pose.tx(), pose.ty(), pose.tz())

        val anchorNode = AnchorNode(
            engine = arSceneView.engine,
            anchor = hitResult.createAnchor(),
        )

        arSceneView.addChildNode(anchorNode)
        placedAnchors.add(anchorNode)

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

    private fun distanceMeters(a: Float3, b: Float3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    override fun onDestroy() {
        super.onDestroy()
        arSceneView.destroy()
    }
}
