package com.example.pracazaliczeniowa

import android.os.Bundle
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode

class ARActivity : AppCompatActivity() {

    private lateinit var arSceneView: ARSceneView
    private lateinit var statusText: TextView
    private lateinit var dimensionOverlay: DimensionOverlayView

    private var modelPlaced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        arSceneView      = findViewById(R.id.arSceneView)
        statusText       = findViewById(R.id.statusText)
        dimensionOverlay = findViewById(R.id.dimensionOverlay)

        arSceneView.lifecycle = this.lifecycle

        arSceneView.onTouchEvent = { motionEvent, _ ->
            if (!modelPlaced && motionEvent.action == MotionEvent.ACTION_UP) {
                val hit = arSceneView.hitTestAR(
                    xPx          = motionEvent.x,
                    yPx          = motionEvent.y,
                    planeTypes   = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                    trackingStates = setOf(TrackingState.TRACKING),
                )
                if (hit != null) placeModel(hit)
            }
            true
        }
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

        // Load the model (async, handled inside MeasurableModelNode)
        measurableNode.loadModel("models/cat.glb", initialScale = 0.5f)

        // Attach to an anchor at the tapped position
        val anchorNode = AnchorNode(
            engine = arSceneView.engine,
            anchor = hitResult.createAnchor(),
        )
        anchorNode.addChildNode(measurableNode)
        arSceneView.addChildNode(anchorNode)
    }

    override fun onDestroy() {
        super.onDestroy()
        arSceneView.destroy()
    }
}