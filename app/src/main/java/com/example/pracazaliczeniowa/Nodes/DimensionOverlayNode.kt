package com.example.pracazaliczeniowa.Nodes

import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node

/**
 * Draws a colour-coded wireframe bounding box around [targetNode].
 *
 * Dimension labels are intentionally NOT rendered in AR space.
 * Instead, call [getDimensions] and show the result in the 2-D HUD
 * (view_model_dimension_label included in activity_ar.xml).
 */
class DimensionOverlayNode(
    engine: Engine,
    private val materialLoader: MaterialLoader,
    private val targetNode: DefaultModelNode
) : Node(engine) {

    private val lineThickness = 0.002f // 2 mm

    // 4 edges per axis – created once, updated on every refresh()
    private val widthLines  = List(4) { CubeNode(engine, size = Float3(1f)).also { addChildNode(it) } }
    private val heightLines = List(4) { CubeNode(engine, size = Float3(1f)).also { addChildNode(it) } }
    private val depthLines  = List(4) { CubeNode(engine, size = Float3(1f)).also { addChildNode(it) } }

    // X = Red, Y = Green, Z = Blue  (matches the HUD colour dots)
    private val colorX = Float4(1f, 0f, 0f, 1f)
    private val colorY = Float4(0f, 1f, 0f, 1f)
    private val colorZ = Float4(0f, 0f, 1f, 1f)

    init {
        refresh()
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /** Call this whenever the model scale changes so the cage stays accurate. */
    fun refresh() {
        val box = targetNode.modelInstance.asset.boundingBox
        val s   = targetNode.scale

        val w = box.halfExtent[0] * 2f * s.x
        val h = box.halfExtent[1] * 2f * s.y
        val d = box.halfExtent[2] * 2f * s.z

        val center = Float3(
            box.center[0] * s.x,
            box.center[1] * s.y,
            box.center[2] * s.z
        )

        updateAxisLines(widthLines,  Float3(w, lineThickness, lineThickness), center, h, d, colorX, "X")
        updateAxisLines(heightLines, Float3(lineThickness, h, lineThickness), center, w, d, colorY, "Y")
        updateAxisLines(depthLines,  Float3(lineThickness, lineThickness, d), center, w, h, colorZ, "Z")
    }

    /**
     * Returns the current world-space dimensions in metres as a [Triple]
     * (width, height, depth).  Multiply by 100 for centimetres, 1000 for mm.
     */
    fun getDimensions(): Triple<Float, Float, Float> {
        val box = targetNode.modelInstance.asset.boundingBox
        val s   = targetNode.scale
        return Triple(
            box.halfExtent[0] * 2f * s.x,
            box.halfExtent[1] * 2f * s.y,
            box.halfExtent[2] * 2f * s.z
        )
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    private fun updateAxisLines(
        lines: List<CubeNode>,
        sizeVec: Float3,
        center: Float3,
        dimA: Float,
        dimB: Float,
        color: Float4,
        axis: String
    ) {
        var idx = 0
        for (i in listOf(-1f, 1f)) {
            for (j in listOf(-1f, 1f)) {
                lines[idx++].apply {
                    scale            = sizeVec
                    materialInstance = materialLoader.createColorInstance(color)
                    position         = when (axis) {
                        "X"  -> center + Float3(0f,           (dimA / 2) * i, (dimB / 2) * j)
                        "Y"  -> center + Float3((dimA / 2) * i, 0f,           (dimB / 2) * j)
                        else -> center + Float3((dimA / 2) * i, (dimB / 2) * j, 0f          )
                    }
                }
            }
        }
    }
}