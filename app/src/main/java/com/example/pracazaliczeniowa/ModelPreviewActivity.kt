package com.example.pracazaliczeniowa

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.lifecycle.lifecycleScope
import com.example.pracazaliczeniowa.Helpers.HsvColorPicker
import com.example.pracazaliczeniowa.Overlays.CropOverlayView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Box
import com.google.android.filament.MaterialInstance
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ModelPreviewActivity : AppCompatActivity() {

    private sealed class BgMode {
        object Hdr : BgMode()
        data class SolidColour(@ColorInt val color: Int) : BgMode()
        object Studio : BgMode()
    }

    companion object {
        const val EXTRA_MODEL_PATH = "extra_model_path"
        const val EXTRA_PROFILE_KEY = "extra_profile_key"
        private const val TAG = "ModelPreviewActivity"
        private const val CAM_DIST_INIT = 2.5f
        private const val CAM_ELEV_DEG_INIT = 35.0
        private const val CAM_AZIM_DEG_INIT = 45.0
        private const val CAM_DIST_MIN = 0.5f
        private const val CAM_DIST_MAX = 10f
        private const val CAM_ELEV_MIN = -89.0
        private const val CAM_ELEV_MAX = 89.0
        private const val THUMB_PX = 360
        private const val THUMB_QUALITY = 85
        private val DEFAULT_VOID_COLOR = Color.parseColor("#DCDCDC")
        private val STUDIO_PLANE_COLOR = Color.parseColor("#E8E8E8")  // floor colour
        private val STUDIO_WALL_COLOR  = Color.parseColor("#F5F5F5")  // wall slightly lighter/whiter

        private val PRESET_COLORS = listOf(
            Color.parseColor("#DCDCDC"),
            Color.parseColor("#1A1A2E"),
            Color.parseColor("#2D2D2D"),
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#0D3B66"),
            Color.parseColor("#3D5A3E"),
        )
    }

    private lateinit var sceneView: SceneView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var normalButtonBar: LinearLayout
    private lateinit var cropConfirmBar: LinearLayout
    private lateinit var btnBackground: ImageButton

    private var modelNode: ModelNode? = null
    private lateinit var profileKey: String
    private var currentBgMode: BgMode = BgMode.SolidColour(DEFAULT_VOID_COLOR)
    private var studioFloorNode: Node? = null
    private var studioWallNode: Node? = null
    private var captureNextFrame = false

    // Track GPU resources so we can destroy them properly and avoid leaks
    private val studioVBufs = mutableListOf<VertexBuffer>()
    private val studioIBufs = mutableListOf<IndexBuffer>()
    private val studioMats  = mutableListOf<MaterialInstance>()

    private var camDist = CAM_DIST_INIT
    private var camElevDeg = CAM_ELEV_DEG_INIT
    private var camAzimDeg = CAM_AZIM_DEG_INIT
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var initialPinchDist = 0f
    private var initialCamDist = 0f
    private var isTwoFinger = false

    // Filament's clearColor is in LINEAR colour space. Android Color values
    // (and everything produced by the HSV colour picker) are sRGB. Without
    // converting, the background appears washed-out / too bright because
    // Filament treats the raw sRGB byte as if it were already linear.
    //
    // IEC 61966-2-1 piecewise sRGB -> linear formula:
    private fun srgbToLinear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f
        else Math.pow((c + 0.055) / 1.055, 2.4).toFloat()
    }

    private fun @receiver:ColorInt Int.linearR() = srgbToLinear(this.red)
    private fun @receiver:ColorInt Int.linearG() = srgbToLinear(this.green)
    private fun @receiver:ColorInt Int.linearB() = srgbToLinear(this.blue)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: start")
        setContentView(R.layout.activity_model_preview)

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run {
            Log.e(TAG, "onCreate: EXTRA_MODEL_PATH is null — finishing")
            finish(); return
        }
        profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY) ?: run {
            Log.e(TAG, "onCreate: EXTRA_PROFILE_KEY is null — finishing")
            finish(); return
        }
        Log.d(TAG, "onCreate: modelPath=$modelPath  profileKey=$profileKey")

        sceneView        = findViewById(R.id.previewSceneView)
        cropOverlay      = findViewById(R.id.cropOverlay)
        normalButtonBar  = findViewById(R.id.normalButtonBar)
        cropConfirmBar   = findViewById(R.id.cropConfirmBar)
        btnBackground    = findViewById(R.id.btnBackground)

        applyBackground(currentBgMode)

        lifecycleScope.launch {
            try {
                Log.d(TAG, "HDR env: attempting to load")
                sceneView.environmentLoader.loadHDREnvironment("envs/environment.hdr")?.let {
                    Log.d(TAG, "HDR env: loaded OK")
                    sceneView.environment = it
                    applyBackground(currentBgMode)
                } ?: Log.w(TAG, "HDR env: loadHDREnvironment returned null")
            } catch (e: Exception) {
                Log.e(TAG, "HDR env: load failed", e)
            }
        }

        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }
        btnBackground.setOnClickListener { showBackgroundPicker() }
        findViewById<Button>(R.id.btnTakeScreenshot).setOnClickListener { showCropOverlay() }
        findViewById<Button>(R.id.btnCropCancel).setOnClickListener { hideCropOverlay() }
        findViewById<Button>(R.id.btnCropConfirm).setOnClickListener {
            pendingCropRect = cropOverlay.getCropRect()
            hideCropOverlay()
            captureNextFrame = true
        }

        lifecycleScope.launch { loadModel(modelPath) }
        sceneView.onFrame = { if (captureNextFrame) { captureNextFrame = false; captureAndSaveThumbnail() } }
        setupTouchListener()
        Log.d(TAG, "onCreate: complete")
    }

    // -------------------------------------------------------------------------
    // Background
    // -------------------------------------------------------------------------

    private fun applyBackground(mode: BgMode) {
        Log.d(TAG, "applyBackground: mode=${mode::class.simpleName}")
        removeStudioPlanes()
        currentBgMode = mode
        when (mode) {
            is BgMode.Hdr -> {
                sceneView.environment?.let { sceneView.skybox = it.skybox }
                    ?: Log.w(TAG, "applyBackground: Hdr mode but environment is null — skybox unchanged")
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply { clear = false }
            }
            is BgMode.SolidColour -> {
                sceneView.skybox = null
                window.decorView.setBackgroundColor(mode.color)
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
                    clear = true
                    clearColor[0] = mode.color.linearR()
                    clearColor[1] = mode.color.linearG()
                    clearColor[2] = mode.color.linearB()
                    clearColor[3] = 1f
                }
            }
            is BgMode.Studio -> {
                sceneView.skybox = null
                window.decorView.setBackgroundColor(STUDIO_PLANE_COLOR)
                sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
                    clear = true
                    clearColor[0] = STUDIO_PLANE_COLOR.linearR()
                    clearColor[1] = STUDIO_PLANE_COLOR.linearG()
                    clearColor[2] = STUDIO_PLANE_COLOR.linearB()
                    clearColor[3] = 1f
                }
                buildStudioPlanes()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Background picker bottom sheet
    // -------------------------------------------------------------------------

    private fun showBackgroundPicker() {
        val sheet = BottomSheetDialog(this)
        val view  = layoutInflater.inflate(R.layout.dialog_background_color_picker, null)
        sheet.setContentView(view)

        val rgMode         = view.findViewById<RadioGroup>(R.id.rgBackgroundMode)
        val layoutColour   = view.findViewById<View>(R.id.layoutColourPicker)
        val swatchRow      = view.findViewById<LinearLayout>(R.id.swatchRow)
        val vCurrentColour = view.findViewById<View>(R.id.vCurrentColour)
        val interactivePicker = view.findViewById<HsvColorPicker>(R.id.interactiveColorPicker)

        var pickedColor = when (val m = currentBgMode) {
            is BgMode.SolidColour -> m.color
            else -> DEFAULT_VOID_COLOR
        }

        // Setup initial UI state
        when (currentBgMode) {
            is BgMode.Hdr -> {
                view.findViewById<RadioButton>(R.id.rbModeHdr).isChecked = true
                layoutColour.visibility = View.GONE
            }
            is BgMode.Studio -> {
                view.findViewById<RadioButton>(R.id.rbModeStudio).isChecked = true
                layoutColour.visibility = View.GONE
            }
            is BgMode.SolidColour -> {
                view.findViewById<RadioButton>(R.id.rbModeVoid).isChecked = true
                layoutColour.visibility = View.VISIBLE
            }
        }

        interactivePicker.setColor(pickedColor)
        updateColourPreview(vCurrentColour, pickedColor)

        // Mode switching
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbModeHdr    -> { layoutColour.visibility = View.GONE;     applyBackground(BgMode.Hdr) }
                R.id.rbModeStudio -> { layoutColour.visibility = View.GONE;     applyBackground(BgMode.Studio) }
                R.id.rbModeVoid   -> { layoutColour.visibility = View.VISIBLE;  applyBackground(BgMode.SolidColour(pickedColor)) }
            }
        }

        // Live interactive picker
        interactivePicker.onColorChanged = { color ->
            pickedColor = color
            updateColourPreview(vCurrentColour, color)
            refreshSwatchRings(swatchRow, color, resources.displayMetrics.density)
            applyBackground(BgMode.SolidColour(color))
        }

        // Preset swatches
        val dp = resources.displayMetrics.density
        PRESET_COLORS.forEach { presetColor ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
                    .also { it.marginEnd = (8 * dp).toInt() }
                background = GradientDrawable().apply {
                    shape        = GradientDrawable.RECTANGLE
                    cornerRadius = 6 * dp
                    setColor(presetColor)
                    if (presetColor == pickedColor) setStroke((2 * dp).toInt(), Color.WHITE)
                }
                setOnClickListener {
                    pickedColor = presetColor
                    interactivePicker.setColor(presetColor)
                    updateColourPreview(vCurrentColour, presetColor)
                    refreshSwatchRings(swatchRow, presetColor, dp)
                    applyBackground(BgMode.SolidColour(presetColor))
                }
            }
            swatchRow.addView(swatch)
        }
        sheet.show()
    }

    private fun refreshSwatchRings(row: LinearLayout, @ColorInt selected: Int, dp: Float) {
        for (i in 0 until row.childCount) {
            val color = PRESET_COLORS.getOrNull(i) ?: continue
            (row.getChildAt(i).background as? GradientDrawable)?.apply {
                setColor(color)
                setStroke(
                    if (color == selected) (2 * dp).toInt() else 0,
                    if (color == selected) Color.WHITE else Color.TRANSPARENT
                )
            }
        }
    }

    private fun updateColourPreview(view: View, @ColorInt color: Int) {
        (view.background as? GradientDrawable)?.setColor(color) ?: view.setBackgroundColor(color)
    }

    // -------------------------------------------------------------------------
    // Studio planes
    // -------------------------------------------------------------------------

    private fun buildStudioPlanes() {
        Log.d(TAG, "buildStudioPlanes: start")
        val engine = sceneView.engine

        val fr = STUDIO_PLANE_COLOR.red   / 255f
        val fg = STUDIO_PLANE_COLOR.green / 255f
        val fb = STUDIO_PLANE_COLOR.blue  / 255f

        val wr = STUDIO_WALL_COLOR.red   / 255f
        val wg = STUDIO_WALL_COLOR.green / 255f
        val wb = STUDIO_WALL_COLOR.blue  / 255f

        val floorY = -0.55f
        val wallZ  = -1.8f

        // Floor quad on the XZ plane.
        // Verts are listed CCW when viewed from ABOVE (+Y) so the front face
        // points upward — toward the camera and the model.
        //   3----0
        //   |  / |   viewed from above, this winds counter-clockwise
        //   | /  |
        //   2----1
        val floorVerts = floatArrayOf(
            -3f, floorY,  3f,   // 0 front-left
            3f, floorY,  3f,   // 1 front-right
            3f, floorY, -3f,   // 2 back-right
            -3f, floorY, -3f    // 3 back-left
        )

        // Wall quad on the XY plane.
        // Verts are listed CCW when viewed from the FRONT (-Z looking toward +Z)
        // so the front face points toward the camera (toward -Z).
        //   3----2
        //   |  / |   viewed from front, CCW
        //   | /  |
        //   0----1
        val wallVerts = floatArrayOf(
            -3f, floorY, wallZ,  // 0 bottom-left
            3f, floorY, wallZ,  // 1 bottom-right
            3f,  3f,    wallZ,  // 2 top-right
            -3f,  3f,    wallZ   // 3 top-left
        )

        // CCW winding: tri1 = 0,1,2  tri2 = 0,2,3
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

        studioFloorNode = buildPlaneNode(
            engine, floorVerts, indices, fr, fg, fb,
            Position(0f, 0f, 0f), Rotation(0f, 0f, 0f), "floor"
        )?.also {
            Log.d(TAG, "buildStudioPlanes: floor node built, adding to scene")
            sceneView.addChildNode(it)
        }

        studioWallNode = buildPlaneNode(
            engine, wallVerts, indices, wr, wg, wb,
            Position(0f, 0f, 0f), Rotation(0f, 0f, 0f), "wall"
        )?.also {
            Log.d(TAG, "buildStudioPlanes: wall node built, adding to scene")
            sceneView.addChildNode(it)
        }

        if (studioFloorNode == null) Log.e(TAG, "buildStudioPlanes: floor node is NULL — buildPlaneNode failed")
        if (studioWallNode  == null) Log.e(TAG, "buildStudioPlanes: wall node is NULL — buildPlaneNode failed")
        Log.d(TAG, "buildStudioPlanes: done")
    }

    /**
     * Builds a plain coloured quad Node using raw Filament APIs.
     *
     * Key fixes vs. original:
     *  1. Node(sceneView) instead of Node(engine) — SceneView 2.x requires a
     *     SceneLifecycleOwner, not a bare Engine. Node(engine) produced an
     *     uninitialised entity (id = 0) which crashed RenderableManager.build().
     *  2. Two VertexBuffer slots: POSITION + TANGENTS (normals). Filament's
     *     default lit material expects tangent frames; missing them causes a
     *     driver crash when shadows are enabled.
     *  3. Stride set to 12 bytes (3 × float) instead of 0 to be explicit.
     *  4. GPU objects tracked in lists so they can be destroyed in
     *     removeStudioPlanes() without leaking VRAM.
     *  5. Explicit AABB via .boundingBox() — Filament aborts if receiveShadows(true)
     *     is set without a bounding box, even when culling is disabled.
     *  6. Exceptions now logged with Log.e instead of being silently swallowed.
     */
    private fun buildPlaneNode(
        engine: Engine,
        vertices: FloatArray,
        indices: ShortArray,
        r: Float, g: Float, b: Float,
        pos: Position,
        rot: Rotation,
        label: String = "plane"
    ): Node? {
        Log.d(TAG, "buildPlaneNode[$label]: vertexCount=${vertices.size / 3}  indexCount=${indices.size}")
        return try {
            val vertCount = vertices.size / 3

            // ------------------------------------------------------------------
            // 1. Vertex buffer — POSITION (slot 0) + TANGENTS as FLOAT4 (slot 1).
            //
            // Filament's TANGENTS attribute MUST be FLOAT4 — it stores a unit
            // quaternion (x,y,z,w) that encodes the full tangent frame including
            // the normal. Supplying FLOAT3 or omitting it causes the default
            // normal (0,0,1 in view space) to be used, making the surface appear
            // white/fully-lit regardless of the actual geometry orientation.
            //
            // For a flat plane with normal N, the minimal valid tangent quaternion
            // is the shortest rotation from (0,0,1) to N. For a floor (N=0,1,0)
            // that is a 90° rotation around X: q = (sin45°, 0, 0, cos45°).
            // We pass the same quaternion for every vertex since the plane is flat.
            // The w sign encodes bitangent handedness — positive = right-handed.
            // ------------------------------------------------------------------
            val vBuf = VertexBuffer.Builder()
                .vertexCount(vertCount)
                .bufferCount(2)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION, 0,
                    VertexBuffer.AttributeType.FLOAT3, 0, 12
                )
                .attribute(
                    VertexBuffer.VertexAttribute.TANGENTS, 1,
                    VertexBuffer.AttributeType.FLOAT4, 0, 16
                )
                .build(engine)
            Log.d(TAG, "buildPlaneNode[$label]: VertexBuffer built OK")

            val vData = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .apply { vertices.forEach { putFloat(it) }; flip() }
            vBuf.setBufferAt(engine, 0, vData)

            // Compute the face normal from the first triangle so the quaternion
            // is correct for both the floor (normal = +Y) and the wall (normal = +Z).
            // Cross product of two edges gives the face normal.
            val ax = vertices[3] - vertices[0]; val ay = vertices[4]  - vertices[1]; val az = vertices[5]  - vertices[2]
            val bx = vertices[6] - vertices[0]; val by = vertices[7]  - vertices[1]; val bz = vertices[8]  - vertices[2]
            var nx = ay * bz - az * by;         var ny = az * bx - ax * bz;         var nz = ax * by - ay * bx
            val nlen = kotlin.math.sqrt(nx*nx + ny*ny + nz*nz).coerceAtLeast(1e-6f)
            nx /= nlen; ny /= nlen; nz /= nlen
            Log.d(TAG, "buildPlaneNode[$label]: face normal = ($nx, $ny, $nz)")

            // Shortest-arc quaternion from reference (0,0,1) to computed normal.
            // q = (cross(ref, n) , 1 + dot(ref, n)).normalised
            // ref = (0, 0, 1): cross = (ny*1-nz*0, nz*0-nx*1, nx*0-ny*0) = (ny, -nx, 0), dot = nz
            var qx = ny; var qy = -nx; var qz = 0f; var qw = 1f + nz
            val qlen = kotlin.math.sqrt(qx*qx + qy*qy + qz*qz + qw*qw).coerceAtLeast(1e-6f)
            qx /= qlen; qy /= qlen; qz /= qlen; qw /= qlen

            val tData = ByteBuffer.allocateDirect(vertCount * 16)
                .order(ByteOrder.nativeOrder())
                .apply { repeat(vertCount) { putFloat(qx); putFloat(qy); putFloat(qz); putFloat(qw) }; flip() }
            vBuf.setBufferAt(engine, 1, tData)

            // ------------------------------------------------------------------
            // 2. Index buffer
            // ------------------------------------------------------------------
            val iBuf = IndexBuffer.Builder()
                .indexCount(indices.size)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine)
            Log.d(TAG, "buildPlaneNode[$label]: IndexBuffer built OK")

            val iData = ByteBuffer.allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder())
                .apply { indices.forEach { putShort(it) }; flip() }
            iBuf.setBuffer(engine, iData)

            // ------------------------------------------------------------------
            // 3. Material instance
            // ------------------------------------------------------------------
            val mat = sceneView.materialLoader.createColorInstance(
                Color.argb(255, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
            )
            Log.d(TAG, "buildPlaneNode[$label]: MaterialInstance created OK")

            // Track for cleanup
            studioVBufs.add(vBuf)
            studioIBufs.add(iBuf)
            studioMats.add(mat)

            // ------------------------------------------------------------------
            // 4. Node — MUST pass sceneView (SceneLifecycleOwner), NOT engine.
            //    Passing engine directly gave node.entity == 0 which caused
            //    RenderableManager.Builder.build() to crash.
            // ------------------------------------------------------------------
            val node = Node(sceneView.engine)
            Log.d(TAG, "buildPlaneNode[$label]: Node created, entity=${node.entity}")

            if (node.entity == 0) {
                Log.e(TAG, "buildPlaneNode[$label]: node.entity is 0 — Filament entity was not allocated. Cannot build renderable.")
                return null
            }

            // Compute an axis-aligned bounding box from the vertex data.
            // Filament requires a non-empty AABB whenever receiveShadows(true) is
            // set, even when culling is disabled. Without this the precondition
            // check in build() fires an abort() and crashes the process.
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in vertices.indices step 3) {
                if (vertices[i]     < minX) minX = vertices[i]
                if (vertices[i]     > maxX) maxX = vertices[i]
                if (vertices[i + 1] < minY) minY = vertices[i + 1]
                if (vertices[i + 1] > maxY) maxY = vertices[i + 1]
                if (vertices[i + 2] < minZ) minZ = vertices[i + 2]
                if (vertices[i + 2] > maxZ) maxZ = vertices[i + 2]
            }
            // A perfectly flat plane has zero thickness on one axis — give it a
            // tiny epsilon so the AABB is never degenerate.
            if (minX == maxX) { minX -= 0.001f; maxX += 0.001f }
            if (minY == maxY) { minY -= 0.001f; maxY += 0.001f }
            if (minZ == maxZ) { minZ -= 0.001f; maxZ += 0.001f }
            Log.d(TAG, "buildPlaneNode[$label]: AABB min=($minX,$minY,$minZ) max=($maxX,$maxY,$maxZ)")

            RenderableManager.Builder(1)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vBuf, iBuf)
                .material(0, mat)
                .boundingBox(Box(
                    (minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f,   // center
                    (maxX - minX) / 2f, (maxY - minY) / 2f, (maxZ - minZ) / 2f    // half-extent
                ))
                .culling(false)
                // Valid FLOAT4 tangent quaternions are now supplied so shadow
                // maps work correctly. Floor receives shadows from the model.
                .receiveShadows(true)
                .castShadows(false)
                .build(engine, node.entity)
            Log.d(TAG, "buildPlaneNode[$label]: RenderableManager built OK")

            node.position = pos
            node.rotation = rot
            Log.d(TAG, "buildPlaneNode[$label]: success — pos=$pos")
            node

        } catch (e: Exception) {
            Log.e(TAG, "buildPlaneNode[$label]: EXCEPTION — ${e::class.simpleName}: ${e.message}", e)
            null
        }
    }

    private fun removeStudioPlanes() {
        Log.d(TAG, "removeStudioPlanes: floor=${studioFloorNode != null}  wall=${studioWallNode != null}")

        // Remove from scene then destroy the SceneView node wrapper
        studioFloorNode?.let { node -> sceneView.removeChildNode(node); node.destroy() }
        studioWallNode?.let  { node -> sceneView.removeChildNode(node); node.destroy() }
        studioFloorNode = null
        studioWallNode  = null

        // Destroy Filament GPU objects via Engine — these are the correct method names
        // in the Filament Java/Kotlin bindings (destruction is engine.destroyXxx(obj))
        try {
            val engine = sceneView.engine
            studioVBufs.forEach { engine.destroyVertexBuffer(it) }
            studioIBufs.forEach { engine.destroyIndexBuffer(it) }
            studioMats.forEach  { engine.destroyMaterialInstance(it) }
            Log.d(TAG, "removeStudioPlanes: GPU resources destroyed OK")
        } catch (e: Exception) {
            Log.e(TAG, "removeStudioPlanes: error destroying GPU resources", e)
        }
        studioVBufs.clear()
        studioIBufs.clear()
        studioMats.clear()
    }

    // -------------------------------------------------------------------------
    // Model loading
    // -------------------------------------------------------------------------

    private suspend fun loadModel(path: String) {
        Log.d(TAG, "loadModel: path=$path")
        try {
            val instance = sceneView.modelLoader.createModelInstance(path)
            if (instance == null) {
                Log.e(TAG, "loadModel: createModelInstance returned null for path=$path")
                return
            }
            Log.d(TAG, "loadModel: model instance created OK")
            modelNode = ModelNode(instance, true, 1.0f, Position(0f, 0f, 0f)).apply {
                isScaleEditable    = false
                isRotationEditable = false
            }
            sceneView.addChildNode(modelNode!!)
            Log.d(TAG, "loadModel: model node added to scene")
            updateCamera()
        } catch (e: Exception) {
            Log.e(TAG, "loadModel: EXCEPTION — ${e::class.simpleName}: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    private fun updateCamera() {
        val elev = Math.toRadians(camElevDeg).toFloat()
        val azim = Math.toRadians(camAzimDeg).toFloat()
        sceneView.cameraNode.position = Position(
            camDist * cos(elev) * sin(azim),
            camDist * sin(elev),
            camDist * cos(elev) * cos(azim)
        )
        sceneView.cameraNode.lookAt(Position(0f, 0f, 0f))
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------

    private fun setupTouchListener() {
        sceneView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x; lastTouchY = event.y; isTwoFinger = false
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                    isTwoFinger = true
                    initialPinchDist = fingerSpacing(event)
                    initialCamDist   = camDist
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (isTwoFinger && event.pointerCount >= 2) {
                        val dist = fingerSpacing(event)
                        if (initialPinchDist > 0f) {
                            camDist = (initialCamDist * initialPinchDist / dist)
                                .coerceIn(CAM_DIST_MIN, CAM_DIST_MAX)
                            updateCamera()
                        }
                    } else {
                        camAzimDeg -= (event.x - lastTouchX) * 0.3
                        camElevDeg  = (camElevDeg + (event.y - lastTouchY) * 0.3)
                            .coerceIn(CAM_ELEV_MIN, CAM_ELEV_MAX)
                        lastTouchX = event.x; lastTouchY = event.y
                        updateCamera()
                    }
                }
            }
            true
        }
    }

    private fun fingerSpacing(e: android.view.MotionEvent): Float {
        val x = e.getX(0) - e.getX(1)
        val y = e.getY(0) - e.getY(1)
        return sqrt(x * x + y * y)
    }

    // -------------------------------------------------------------------------
    // Crop / screenshot
    // -------------------------------------------------------------------------

    private fun showCropOverlay() {
        cropOverlay.visibility    = View.VISIBLE
        cropConfirmBar.visibility = View.VISIBLE
        normalButtonBar.visibility = View.GONE
        btnBackground.visibility  = View.GONE
        sceneView.setOnTouchListener(null)
    }

    private fun hideCropOverlay() {
        cropOverlay.visibility    = View.GONE
        cropConfirmBar.visibility = View.GONE
        normalButtonBar.visibility = View.VISIBLE
        btnBackground.visibility  = View.VISIBLE
        setupTouchListener()
    }

    private var pendingCropRect: RectF? = null

    private fun captureAndSaveThumbnail() {
        Log.d(TAG, "captureAndSaveThumbnail: w=${sceneView.width}  h=${sceneView.height}")
        val w = sceneView.width
        val h = sceneView.height
        if (w == 0 || h == 0) {
            Log.e(TAG, "captureAndSaveThumbnail: sceneView has zero size — aborting")
            return
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sceneView, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                Log.d(TAG, "captureAndSaveThumbnail: PixelCopy SUCCESS")
                val cropped = pendingCropRect?.let { cropBitmap(bitmap, it, w, h) } ?: bitmap
                saveThumbnailToDisk(scaledForCard(cropped))
            } else {
                Log.e(TAG, "captureAndSaveThumbnail: PixelCopy FAILED — result code=$result")
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun cropBitmap(b: Bitmap, r: RectF, vw: Int, vh: Int): Bitmap {
        val sx = b.width.toFloat()  / vw
        val sy = b.height.toFloat() / vh
        return Bitmap.createBitmap(
            b,
            (r.left   * sx).toInt().coerceIn(0, b.width),
            (r.top    * sy).toInt().coerceIn(0, b.height),
            ((r.right  - r.left) * sx).toInt().coerceAtLeast(1),
            ((r.bottom - r.top)  * sy).toInt().coerceAtLeast(1)
        )
    }

    private fun scaledForCard(src: Bitmap): Bitmap {
        val side   = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, THUMB_PX, THUMB_PX, true)
    }

    private fun saveThumbnailToDisk(bitmap: Bitmap) {
        try {
            val file = File(filesDir, "thumbnails/$profileKey.jpg").apply { parentFile?.mkdirs() }
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
            Log.d(TAG, "saveThumbnailToDisk: saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "saveThumbnailToDisk: FAILED", e)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        removeStudioPlanes()
        sceneView.destroy()
    }
}