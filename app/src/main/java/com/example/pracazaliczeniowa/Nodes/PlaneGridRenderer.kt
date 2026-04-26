package com.example.pracazaliczeniowa.Nodes

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.MeshNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.abs

/**
 * PlaneGridRenderer
 * ─────────────────
 * Custom plane visualizer that renders a white-outlined grid of 5 cm cells
 * over every tracked ARCore plane, respecting the wall-magnet mode toggle.
 *
 * Architecture
 * ────────────
 * • One [AnchorNode] + [MeshNode] pair per tracked [Plane].
 *   The [AnchorNode] is created from the plane's center pose — SceneView
 *   drives the world transform automatically, so we never manually assign
 *   worldQuaternion (which was the cause of the vertical-strip bug).
 * • Grid geometry is a set of thin flat quads (line thickness = 3 mm) that
 *   form a [CELL_M] × [CELL_M] grid.  No texture needed — lines are geometry.
 * • The mesh is rebuilt whenever the plane's extents grow by more than one
 *   cell width, so the grid expands naturally with the detected surface.
 * • Floor planes → blue tint.  Wall planes → orange tint.
 * • Only planes that match the current wall-magnet mode are rendered.
 *
 * Usage (ARActivity)
 * ──────────────────
 *   private lateinit var planeGridRenderer: PlaneGridRenderer
 *
 *   // In onCreate after arSceneView.lifecycle = lifecycle:
 *   arSceneView.planeRenderer.isEnabled = false
 *   planeGridRenderer = PlaneGridRenderer(arSceneView)
 *   arSceneView.onFrame = { _ -> planeGridRenderer.update(isWallMagnetVertical) }
 *
 *   // In closeScene() and the !isClosing branch of onDestroy():
 *   planeGridRenderer.destroy()
 */
class PlaneGridRenderer(private val sceneView: ARSceneView) {

    // ── Tuneable constants ────────────────────────────────────────────────────

    /** Physical size of one grid cell in metres (5 cm). */
    private val CELL_M = 0.05f

    /** Width of each rendered grid line in metres (3 mm). */
    private val LINE_M = 0.003f

    private val HALF_LINE = LINE_M / 2f

    /** Rebuild the mesh when extents change by more than this. */
    private val REBUILD_THRESHOLD = CELL_M

    // Filament colour instances: floor = blue, wall = orange.
    // Alpha is intentionally low so the real world shows through.
    private val COLOR_FLOOR = Float4(0.35f, 0.65f, 1.00f, 0.55f)
    private val COLOR_WALL  = Float4(1.00f, 0.60f, 0.20f, 0.55f)

    // ── Per-plane bookkeeping ─────────────────────────────────────────────────

    private data class PlaneEntry(
        val anchor:      Anchor,
        val anchorNode:  AnchorNode,
        var meshNode:    MeshNode,
        var lastExtentX: Float,
        var lastExtentZ: Float
    )

    private val engine: Engine get() = sceneView.engine
    private val entries = mutableMapOf<Int, PlaneEntry>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Call every frame from [ARSceneView.onFrame].
     * [wallMagnetActive] mirrors [ARActivity.isWallMagnetVertical].
     */
    fun update(wallMagnetActive: Boolean) {
        val session = sceneView.session ?: return
        val allPlanes = session.getAllTrackables(Plane::class.java)

        for (plane in allPlanes) {
            val key        = System.identityHashCode(plane)
            val isVertical = plane.type == Plane.Type.VERTICAL
            val shouldShow = if (wallMagnetActive) isVertical else !isVertical

            if (!shouldShow
                || plane.trackingState != TrackingState.TRACKING
                || plane.subsumedBy != null
            ) {
                removeEntry(key)
                continue
            }

            val extX = plane.extentX
            val extZ = plane.extentZ

            when (val entry = entries[key]) {
                null -> {
                    // First time we see this plane — create anchor + mesh
                    val anchor     = plane.createAnchor(plane.centerPose)
                    val anchorNode = AnchorNode(engine, anchor).also {
                        sceneView.addChildNode(it)
                    }
                    val color    = if (isVertical) COLOR_WALL else COLOR_FLOOR
                    val meshNode = buildGridMesh(extX, extZ, color).also {
                        anchorNode.addChildNode(it)
                    }
                    entries[key] = PlaneEntry(anchor, anchorNode, meshNode, extX, extZ)
                }
                else -> {
                    // Existing entry — rebuild mesh if the plane has grown
                    val dX = abs(extX - entry.lastExtentX)
                    val dZ = abs(extZ - entry.lastExtentZ)
                    if (dX > REBUILD_THRESHOLD || dZ > REBUILD_THRESHOLD) {
                        entry.anchorNode.removeChildNode(entry.meshNode)
                        entry.meshNode.destroy()

                        val color   = if (isVertical) COLOR_WALL else COLOR_FLOOR
                        val newMesh = buildGridMesh(extX, extZ, color).also {
                            entry.anchorNode.addChildNode(it)
                        }
                        entry.meshNode    = newMesh
                        entry.lastExtentX = extX
                        entry.lastExtentZ = extZ
                    }
                }
            }
        }

        // Remove entries for planes that are no longer tracked
        val liveKeys = allPlanes.map { System.identityHashCode(it) }.toSet()
        (entries.keys - liveKeys).toList().forEach { removeEntry(it) }
    }

    /** Release all scene nodes and Filament GPU resources. */
    fun destroy() {
        entries.keys.toList().forEach { removeEntry(it) }
    }

    // ── Grid mesh builder ─────────────────────────────────────────────────────

    /**
     * Builds a flat mesh of [CELL_M] × [CELL_M] grid cells spanning
     * [extX] × [extZ] metres, centred on the origin.
     *
     * Grid lines are thin rectangular quads (two triangles each).
     * Coordinates:  X and Z in the plane surface, Y = 0 (flush with surface).
     *
     * For a **floor plane** the AnchorNode's pose has Y pointing up, so the
     * mesh lies flat on the floor — correct.
     *
     * For a **wall plane** ARCore's pose has Y pointing away from the wall
     * (the plane normal), so the same XZ mesh appears flush with the wall
     * face — also correct, no manual rotation needed.
     */
    private fun buildGridMesh(extX: Float, extZ: Float, color: Float4): MeshNode {

        // Snap extents outward to the nearest cell boundary
        val halfX = ceil(extX / (2f * CELL_M)).toFloat() * CELL_M
        val halfZ = ceil(extZ / (2f * CELL_M)).toFloat() * CELL_M

        // Count lines in each direction (edges + every interior step)
        val stepsX   = ceil((halfX * 2f) / CELL_M).toInt() + 1
        val stepsZ   = ceil((halfZ * 2f) / CELL_M).toInt() + 1
        val lineCount = stepsX + stepsZ

        val vertexCount = lineCount * 4
        val indexCount  = lineCount * 6

        val positions = FloatArray(vertexCount * 3)
        val indices   = ShortArray(indexCount)

        var vIdx = 0   // float index into positions
        var iIdx = 0   // short index into indices
        var vi   = 0   // vertex counter (for index values)

        fun pushLine(x0: Float, z0: Float, x1: Float, z1: Float) {
            // Quad at Y = 0
            positions[vIdx++] = x0;  positions[vIdx++] = 0f;  positions[vIdx++] = z0
            positions[vIdx++] = x1;  positions[vIdx++] = 0f;  positions[vIdx++] = z0
            positions[vIdx++] = x1;  positions[vIdx++] = 0f;  positions[vIdx++] = z1
            positions[vIdx++] = x0;  positions[vIdx++] = 0f;  positions[vIdx++] = z1
            // Triangle 1: 0-1-2
            indices[iIdx++] = vi.toShort()
            indices[iIdx++] = (vi + 1).toShort()
            indices[iIdx++] = (vi + 2).toShort()
            // Triangle 2: 0-2-3
            indices[iIdx++] = vi.toShort()
            indices[iIdx++] = (vi + 2).toShort()
            indices[iIdx++] = (vi + 3).toShort()
            vi += 4
        }

        // Lines running parallel to Z (step along X)
        var x = -halfX
        while (x <= halfX + 0.0001f) {
            pushLine(x - HALF_LINE, -halfZ, x + HALF_LINE, halfZ)
            x += CELL_M
        }

        // Lines running parallel to X (step along Z)
        var z = -halfZ
        while (z <= halfZ + 0.0001f) {
            pushLine(-halfX, z - HALF_LINE, halfX, z + HALF_LINE)
            z += CELL_M
        }

        // ── Upload positions to Filament ──────────────────────────────────────
        val posBuf = ByteBuffer
            .allocateDirect(vertexCount * 3 * 4)
            .order(ByteOrder.nativeOrder())
        posBuf.asFloatBuffer().put(positions)
        posBuf.rewind()

        val vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                12   // stride = 3 floats × 4 bytes
            )
            .build(engine)
        vb.setBufferAt(engine, 0, posBuf)

        // ── Upload indices to Filament ─────────────────────────────────────────
        val idxBuf = ByteBuffer
            .allocateDirect(indexCount * 2)
            .order(ByteOrder.nativeOrder())
        idxBuf.asShortBuffer().put(indices)
        idxBuf.rewind()

        val ib = IndexBuffer.Builder()
            .indexCount(indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib.setBuffer(engine, idxBuf)

        // Thin bounding slab centred on origin
        val bbox = Box(0f, 0f, 0f, halfX, 0.005f, halfZ)

        val material = sceneView.materialLoader.createColorInstance(color)

        return MeshNode(
            engine           = engine,
            primitiveType    = RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer     = vb,
            indexBuffer      = ib,
            boundingBox      = bbox,
            materialInstance = material
        )
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun removeEntry(key: Int) {
        entries.remove(key)?.also { e ->
            e.anchorNode.removeChildNode(e.meshNode)
            e.meshNode.destroy()
            sceneView.removeChildNode(e.anchorNode)
            e.anchorNode.destroy()
            e.anchor.detach()
        }
    }
}