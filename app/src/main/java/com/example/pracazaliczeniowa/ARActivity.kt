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
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.launch

class ARActivity : AppCompatActivity() {

    private lateinit var arSceneView: ARSceneView
    private lateinit var statusText: TextView
    private var modelPlaced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar)

        arSceneView = findViewById(R.id.arSceneView)
        statusText = findViewById(R.id.statusText)

        arSceneView.lifecycle = this.lifecycle

        // ✅ Correct callback in SceneView 2.x
        arSceneView.onTouchEvent = { motionEvent, hitResult ->
            if (!modelPlaced && motionEvent.action == MotionEvent.ACTION_UP) {
                // hitResult is an ARCore HitResult — check it hit a plane
                val arHitResult = arSceneView.hitTestAR(
                    xPx = motionEvent.x,
                    yPx = motionEvent.y,
                    planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
                    trackingStates = setOf(TrackingState.TRACKING)
                )
                if (arHitResult != null) {
                    placeModel(arHitResult)
                }
            }
            true // consume the event
        }
    }

    private fun placeModel(hitResult: com.google.ar.core.HitResult) {
        modelPlaced = true
        statusText.text = "Loading model..."

        lifecycleScope.launch {
            val modelInstance = arSceneView.modelLoader.createModelInstance(
                assetFileLocation = "models/cat.glb"
            )

            val anchorNode = AnchorNode(
                engine = arSceneView.engine,
                anchor = hitResult.createAnchor()
            )

            val modelNode = ModelNode(
                modelInstance = modelInstance,
                scaleToUnits = 0.5f,
                centerOrigin = Position(y = -1f)
            )

            anchorNode.addChildNode(modelNode)
            arSceneView.addChildNode(anchorNode)

            statusText.text = "Model placed! ✓"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        arSceneView.destroy()
    }
}