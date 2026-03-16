package com.example.pracazaliczeniowa

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class ARActivity : AppCompatActivity() {

    private lateinit var arSceneView: ARSceneView
    private lateinit var statusText: TextView
    private lateinit var dimensionOverlay: DimensionOverlayView
    private lateinit var modelControls: ModelControlOverlayView
    private lateinit var measureOverlay: MeasureTapeOverlayView
    private lateinit var measureModeButton: Button
    private lateinit var unitButton: Button

    private var modelPlaced = false
    private var mode: Mode = Mode.MODEL
    private var unit: DistanceUnit = DistanceUnit.METERS

    private val placedAnchors = mutableListOf<AnchorNode>()
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

        arSceneView.lifecycle = this.lifecycle

        // Load HDR environment for proper IBL + skybox.
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

            // Refresh status text if a measurement is already present.
            if (measurePointA != null && measurePointB != null) {
                val dist = distanceMeters(measurePointA!!, measurePointB!!)
                val (value, suffix) = unit.convert(dist)
                statusText.text = String.format("Distance: %.1f %s", value, suffix)
            }
        }

        arSceneView.onTouchEvent = { motionEvent, _ ->
            if (motionEvent.action == MotionEvent.ACTION_UP) {
                when (mode) {
                    Mode.MODEL -> if (!modelPlaced) {
                        val hit = arSceneView.hitTestAR(
                            xPx = motionEvent.x,
                            yPx = motionEvent.y,
                            planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                            trackingStates = setOf(TrackingState.TRACKING),
                        )
                        if (hit != null) placeModel(hit)
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
                statusText.text = "Point at a surface to place model"
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
        modelPlaced = false

        measurePointA = null
        measurePointB = null
        measureOverlay.clear()

        // Remove all anchors we created (model placement + measure points).
        placedAnchors.forEach { a ->
            a.anchor.detach()
            a.parent = null
        }
        placedAnchors.clear()
    }

    private fun placeModel(hitResult: com.google.ar.core.HitResult) {
        modelPlaced  = true
        statusText.text = "Loading model…"

        // ── Build the measurable node ──────────────────────────────────────
        val measurableNode = MeasurableModelNode(
            engine      = arSceneView.engine,
            modelLoader = arSceneView.modelLoader,
            scope       = lifecycleScope,
        )

        measurableNode.onDimensionsChanged = { w, h, d ->
            statusText.text = "W: ${"%.2f".format(w)} m  " +
                              "H: ${"%.2f".format(h)} m  " +
                              "D: ${"%.2f".format(d)} m"
        }

        // Connect the overlay to the scene and node
        dimensionOverlay.attach(arSceneView, measurableNode)

        // Bind the UI controls to this node (treat as selected model).
        modelControls.bindToNode(measurableNode)

        // Load the model (async, handled inside MeasurableModelNode)
        measurableNode.loadModel("models/cat.glb", initialScale = 0.2f)

        // Attach to an anchor at the tapped position
        val anchorNode = AnchorNode(
            engine = arSceneView.engine,
            anchor = hitResult.createAnchor(),
        )
        anchorNode.addChildNode(measurableNode)
        arSceneView.addChildNode(anchorNode)
        placedAnchors.add(anchorNode)
    }

    private fun placeMeasurePoint(hitResult: com.google.ar.core.HitResult) {
        val pose = hitResult.hitPose
        val point = Float3(pose.tx(), pose.ty(), pose.tz())

        // Create an anchor so the point stays stable in the world.
        val anchorNode = AnchorNode(
            engine = arSceneView.engine,
            anchor = hitResult.createAnchor(),
        )
        arSceneView.addChildNode(anchorNode)
        placedAnchors.add(anchorNode)

        if (measurePointA == null || (measurePointA != null && measurePointB != null)) {
            // Start a new measurement on 1st tap or after finishing a previous one.
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