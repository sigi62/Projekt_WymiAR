package com.example.pracazaliczeniowa.Nodes

import android.content.Context
import android.graphics.Color
import android.widget.TextView
import com.example.pracazaliczeniowa.R
import com.google.android.filament.Engine
import com.google.ar.sceneform.rendering.ViewAttachmentManager
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.ViewNode
import kotlin.collections.get


class DimensionOverlayNode(
    engine: Engine,
    private val modelLoader: ModelLoader,
    private val materialLoader: MaterialLoader,
    private val context: Context,
    private val viewAttachmentManager: ViewAttachmentManager,
    private val targetNode: DefaultModelNode
) : Node(engine) {

    private val lineThickness = 0.002f // 2mm
    private val labelOffset = 0.005f   // 5mm offset from edge

    // Lists to keep track of created nodes for updating
    private val widthLines = List(4) { CubeNode(engine, size = Float3(1f)).also { addChildNode(it) } }
    private val heightLines = List(4) { CubeNode(engine, size = Float3(1f)).also { addChildNode(it) } }
    private val depthLines = List(4) { CubeNode(engine, size = Float3(1f)).also { addChildNode(it) } }

    private val labelW = createLabelNode()
    private val labelH = createLabelNode()
    private val labelD = createLabelNode()

    // Colors
    private val colorX = Float4(1f, 0f, 0f, 1f) // Red
    private val colorY = Float4(0f, 1f, 0f, 1f) // Green
    private val colorZ = Float4(0f, 0f, 1f, 1f) // Blue

    init {

        addChildNode(labelW)
        addChildNode(labelH)
        addChildNode(labelD)
        refresh()
    }

    fun refresh() {
        val box = targetNode.modelInstance.asset.boundingBox
        val s = targetNode.scale

        // Calculate current world dimensions
        val w = box.halfExtent[0] * 2f * s.x
        val h = box.halfExtent[1] * 2f * s.y
        val d = box.halfExtent[2] * 2f * s.z

        val scaledCenter = Float3(box.center[0] * s.x, box.center[1] * s.y, box.center[2] * s.z)

        // 1. Update Wireframe Lines
        updateAxisLines(
            widthLines, Float3(w, lineThickness, lineThickness),
            scaledCenter, h, d,
            colorX, "X"
        )
        updateAxisLines(
            heightLines, Float3(lineThickness, h, lineThickness),
            scaledCenter, w, d,
            colorY, "Y"
        )
        updateAxisLines(
            depthLines, Float3(lineThickness, lineThickness, d),
            scaledCenter, w, h,
            colorZ, "Z"
        )

        // 2. Update Labels (Positioning them at the midpoint of specific edges)
        // Width Label: Top-Front Edge
        updateLabel(
            labelW, "${(w * 100).toInt()} cm",
            scaledCenter + Float3(0f, h / 2 + labelOffset, d / 2), colorX
        )

        // Height Label: Right-Front Edge
        updateLabel(
            labelH, "${(h * 100).toInt()} cm",
            scaledCenter + Float3(w / 2 + labelOffset, 0f, d / 2), colorY
        )

        // Depth Label: Right-Top Edge
        updateLabel(
            labelD, "${(d * 100).toInt()} cm",
            scaledCenter + Float3(w / 2 + labelOffset, h / 2, 0f), colorZ
        )
    }

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
                    scale = sizeVec
                    materialInstance = materialLoader.createColorInstance(color)
                    position = when (axis) {
                        "X" -> center + Float3(0f, (dimA / 2) * i, (dimB / 2) * j)
                        "Y" -> center + Float3((dimA / 2) * i, 0f, (dimB / 2) * j)
                        else -> center + Float3((dimA / 2) * i, (dimB / 2) * j, 0f)
                    }
                }
            }
        }
    }

    private fun createLabelNode(): ViewNode {
        return ViewNode(engine, modelLoader, viewAttachmentManager).apply {
            loadView(context, R.layout.view_model_dimension_label)
        }
    }

    private fun updateLabel(node: ViewNode, text: String, pos: Float3, color: Float4) {
        node.position = pos
        node.quaternion = dev.romainguy.kotlin.math.Quaternion()
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    
        node.onViewLoaded = { _, view ->
            val textView = view.findViewById<TextView>(R.id.dimensionText)
            textView?.text = text
            textView?.setTextColor(
                android.graphics.Color.rgb(
                    (color.x * 255).toInt(),
                    (color.y * 255).toInt(),
                    (color.z * 255).toInt()
                )
            )
        }
    }

    override fun destroy() {
        // Explicitly destroy ViewNodes to clean up Android Views from the manager
        labelW.destroy()
        labelH.destroy()
        labelD.destroy()
        super.destroy()
    }
}

//    private fun createGeometry() {
//        // ... (Move your existing loop logic here, but add nodes to the lists above)
//        // Example for Width:
//
//        val box = targetNode.modelInstance.asset.boundingBox
//
//        // Apply current scale of the model to the bounding box extents
//        val s = targetNode.scale
//        val w = box.halfExtent[0] * 2f *s.x
//        val h = box.halfExtent[1] * 2f * s.y
//        val d = box.halfExtent[2] * 2f * s.z
//
//        // Adjust center based on model scale
//        val scaledCenter = Float3(box.center[0] * s.x, box.center[1] * s.y, box.center[2] * s.z)
//        val lineThickness = 0.002f // 2mm thick lines
//
//        // Colors (X: Red, Y: Green, Z: Blue)
//        val colorX = Float4(1f, 0f, 0f, 1f)
//        val colorY = Float4(0f, 1f, 0f, 1f)
//        val colorZ = Float4(0f, 0f, 1f, 1f)
//
//        // 1. Create Wireframe Edges (4 for each axis)
//
//        // WIDTH Edges (X-axis) - Red
////        for (i in listOf(-1f, 1f)) {
////            for (j in listOf(-1f, 1f)) {
////                addChildNode(
////                    CubeNode(engine, size = Float3(w, lineThickness, lineThickness)).apply {
////                    materialInstance = materialLoader.createColorInstance(colorX)
////                    position = scaledCenter + Float3(0f, (h / 2) * i, (d / 2) * j)
////                }
////                )
////            }
////        }
//        for (i in listOf(-1f, 1f)) {
//            for (j in listOf(-1f, 1f)) {
//                val line = CubeNode(engine, size = Float3(w, lineThickness, lineThickness)).
//                apply {
//                    materialInstance = materialLoader.createColorInstance(colorX)
//                    position = scaledCenter + Float3(0f, (h / 2) * i, (d / 2) * j)
//                }.also { widthLines.add(it) }
//                addChildNode(line)
//            }
//        }
//
//
//        // HEIGHT Edges (Y-axis) - Green
////        for (i in listOf(-1f, 1f)) {
////            for (j in listOf(-1f, 1f)) {
////                addChildNode(CubeNode(engine, size = Float3(lineThickness, h, lineThickness)).apply {
////                    materialInstance = materialLoader.createColorInstance(colorY)
////                    position = scaledCenter + Float3((w / 2) * i, 0f, (d / 2) * j)
////                })
////            }
////        }
//        for (i in listOf(-1f, 1f)) {
//            for (j in listOf(-1f, 1f)) {
//                val line = CubeNode(engine, size = Float3(w, lineThickness, lineThickness)).
//                apply {
//                    materialInstance = materialLoader.createColorInstance(colorX)
//                    position = scaledCenter + Float3((w / 2) * i, 0f, (d / 2) * j)
//                }.also { heightLines.add(it) }
//                addChildNode(line)
//            }
//        }
//
//        // DEPTH Edges (Z-axis) - Blue
////        for (i in listOf(-1f, 1f)) {
////            for (j in listOf(-1f, 1f)) {
////                addChildNode(CubeNode(engine, size = Float3(lineThickness, lineThickness, d)).apply {
////                    materialInstance = materialLoader.createColorInstance(colorZ)
////                    position = scaledCenter + Float3((w / 2) * i, (h / 2) * j, 0f)
////                })
////            }
////        }
//        for (i in listOf(-1f, 1f)) {
//            for (j in listOf(-1f, 1f)) {
//                val line = CubeNode(engine, size = Float3(w, lineThickness, lineThickness)).
//                apply {
//                    materialInstance = materialLoader.createColorInstance(colorX)
//                    position = scaledCenter +  Float3((w / 2) * i, (h / 2) * j, 0f)
//                }.also { depthLines.add(it) }
//                addChildNode(line)
//            }
//        }
//
//        // 2. Add Labels
//        val wMm = "${(w * 1000).toInt()} mm"
//        val hMm = "${(h * 1000).toInt()} mm"
//        val dMm = "${(d * 1000).toInt()} mm"
//
//        // Labels positioned slightly offset from the cage
//        addLabel(context, engine, modelLoader, viewAttachmentManager, wMm,scaledCenter + Float3(0f, h / 2 + 0.02f, d / 2), colorX)
//        addLabel(context, engine, modelLoader, viewAttachmentManager, hMm, scaledCenter + Float3(w / 2 + 0.02f, 0f, d / 2), colorY)
//        addLabel(context, engine, modelLoader, viewAttachmentManager,dMm, scaledCenter + Float3(w / 2 + 0.02f, h / 2, 0f), colorZ)
//
//
//        // ... repeat for Height/Depth and labels
//        refresh(targetNode.scale)
//    }
//
//    fun refresh(s: Float3) {
//        val box = targetNode.modelInstance.asset.boundingBox
//        val w = box.halfExtent[0] * 2f * s.x
//        val h = box.halfExtent[1] * 2f * s.y
//        val d = box.halfExtent[2] * 2f * s.z
//        val scaledCenter = Float3(box.center[0] * s.x, box.center[1] * s.y, box.center[2] * s.z)
//
//        // Update Width Lines
//        var idx = 0
//        for (i in listOf(-1f, 1f)) {
//            for (j in listOf(-1f, 1f)) {
//                widthLines[idx++].apply {
//                    size = Float3(w, lineThickness, lineThickness)
//                    position = scaledCenter + Float3(0f, (h / 2) * i, (d / 2) * j)
//                }
//            }
//        }
//        // ... Update Height/Depth Lines similarly ...
//
//        // Update Label Texts and Positions
//        // (Update the text inside the TextViews and move the ViewNode positions)
//    }
//
//
//    private fun createLabelNode(): ViewNode {
//        return ViewNode(engine, modelLoader, viewAttachmentManager).apply {
//            loadView(context, R.layout.view_model_dimension_label)
//        }
//    }
//
//    private fun addLabel(ctx: Context, eng: Engine, loader: ModelLoader, viewAttachmentManager : ViewAttachmentManager, text: String, pos: Float3, color: Float4) {
//        addChildNode(ViewNode(eng, loader, viewAttachmentManager).apply {
//            loadView(ctx, R.layout.view_model_dimension_label)
//            position = pos
//            onViewLoaded = { _, view ->
//                val textView = view.findViewById<TextView>(R.id.dimensionText)
//                textView?.text = text
//                textView?.setTextColor(android.graphics.Color.rgb(
//                    (color.x * 255).toInt(),
//                    (color.y * 255).toInt(),
//                    (color.z * 255).toInt()
//                ))
//            }
//        })
//    }
//    // Inside DimensionOverlayNode.kt
//    override fun destroy() {
//        super.destroy()
//        // The children (ViewNodes and CubeNodes) are destroyed automatically
//        // when the parent is destroyed, but if you have listeners, clear them here.
//    }
//}
