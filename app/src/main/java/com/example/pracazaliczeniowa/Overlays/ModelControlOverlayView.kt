package com.example.pracazaliczeniowa.Overlays

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode
import com.example.pracazaliczeniowa.R

class ModelControlOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var targetNode: SelectedModelNode? = null
    private var currentMode = "ROTATE"

    // --- State Storage ---
    private val MIDDLE = 100
    private val RANGE_MAX = 200
    private var rotateProgress = Triple(MIDDLE, MIDDLE, MIDDLE)
    private var positionProgress = Triple(MIDDLE, MIDDLE, MIDDLE)
    private var scaleProgress = Triple(MIDDLE, MIDDLE, MIDDLE)
    private var universalScaleProgress = MIDDLE

    var onSaveRequested: (() -> Unit)? = null // Callback for the Activity
    var onDeleteRequested: (() -> Unit)? = null // Callback for the Activity

    init {
        // 1. This is the missing piece: Inflate the XML into this LinearLayout
        LayoutInflater.from(context).inflate(R.layout.view_model_controls, this, true)

        // 2. Set orientation since the root in XML is vertical
        orientation = VERTICAL
        this.isClickable = true
        this.isFocusable = true

    }

    fun bindToNode(node: SelectedModelNode) {
        this.targetNode = node
        setupUI()
    }
    fun updateScaleFromGesture(factor: Float) {
        // Map the pinch factor back to our slider progress (0.0125 factor)
        val newProgress = (factor / 0.0125f).toInt().coerceIn(0, 200)
        universalScaleProgress = newProgress
        if (currentMode == "SCALE") {
            findViewById<SeekBar>(R.id.seekUniversalScale).progress = newProgress
        }
    }

    fun updateRotationFromHandle(yDegrees: Float) {
        // 1. Reverse the math: (degrees / 1.8) + MIDDLE
        // We normalize the degree to be within a standard -180 to 180 range first
        val normalizedDeg = ((yDegrees + 180) % 360) - 180
        val newProgress = (normalizedDeg / 1.8f + MIDDLE).toInt().coerceIn(0, 200)

        // 2. Update state storage
        // We keep X and Z from the previous state, only updating Y (the second value)
        rotateProgress = Triple(rotateProgress.first, newProgress, rotateProgress.third)

        // 3. Update UI if currently in Rotate mode
        if (currentMode == "ROTATE") {
            val s2 = findViewById<SeekBar>(R.id.seek2) // Assuming seek2 is Y-axis
            s2.progress = newProgress
        }
    }


    private fun setupUI() {
        val s1 = findViewById<SeekBar>(R.id.seek1)
        val s2 = findViewById<SeekBar>(R.id.seek2)
        val s3 = findViewById<SeekBar>(R.id.seek3)
        val sUni = findViewById<SeekBar>(R.id.seekUniversalScale)
        val lUni = findViewById<TextView>(R.id.labelUniversalScale)
        val seekBarsLayout = findViewById<LinearLayout>(R.id.seekBarsLayout)

        seekBarsLayout.visibility = View.GONE


        // Reset all sliders to middle on start
        listOf(s1, s2, s3, sUni).forEach {
            it.max = RANGE_MAX
            it.progress = MIDDLE
        }

        val btnRot = findViewById<Button>(R.id.btnModeRotate)
        val btnPos = findViewById<Button>(R.id.btnModePosition)
        val btnScl = findViewById<Button>(R.id.btnModeScale)
        val btnSave = findViewById<Button>(R.id.btnModelSaveProfile)
        val btnDelete = findViewById<Button>(R.id.btnModelDelete)

        fun refreshSlidersForMode() {
            val (p1, p2, p3) = when (currentMode) {
                "ROTATE" -> rotateProgress
                "POSITION" -> positionProgress
                "SCALE" -> scaleProgress
                else -> Triple(MIDDLE, MIDDLE, MIDDLE)
            }
            s1.progress = p1
            s2.progress = p2
            s3.progress = p3
            sUni.progress = universalScaleProgress
        }

        fun toggleMode(mode: String) {
            if (currentMode == mode && seekBarsLayout.visibility == View.VISIBLE) {
                // If clicking the same button again, hide the panel
                seekBarsLayout.visibility = View.GONE
            } else {
                // Show panel and set the mode
                currentMode = mode
                seekBarsLayout.visibility = View.VISIBLE
                refreshSlidersForMode()

                // Toggle universal scale visibility based on mode
                val isScale = mode == "SCALE"
                sUni.visibility = if (isScale) View.VISIBLE else View.GONE
                lUni.visibility = if (isScale) View.VISIBLE else View.GONE
            }
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return

                // 1. Save state
                if (sb?.id == R.id.seekUniversalScale && currentMode == "SCALE") {
                    s1.progress = p
                    s2.progress = p
                    s3.progress = p
                    universalScaleProgress = p
                    scaleProgress = Triple(p, p, p)
                } else {
                    // Save individual state for other sliders
                    when (currentMode) {
                        "ROTATE" -> rotateProgress = Triple(s1.progress, s2.progress, s3.progress)
                        "POSITION" -> positionProgress = Triple(s1.progress, s2.progress, s3.progress)
                        "SCALE" -> scaleProgress = Triple(s1.progress, s2.progress, s3.progress)
                    }
                }

                // 2. Apply to Node
                targetNode?.let { node ->
                    when (currentMode) {
                        "ROTATE" -> node.updateRotation((s1.progress - MIDDLE) * 1.8f, (s2.progress - MIDDLE) * 1.8f, (s3.progress - MIDDLE) * 1.8f)
                        "POSITION" -> node.updatePosition((s1.progress - MIDDLE) * 0.05f, (s2.progress - MIDDLE) * 0.05f, (s3.progress - MIDDLE) * 0.05f)
                        "SCALE" -> node.updateScale(s1.progress * 0.0125f, s2.progress * 0.0125f, s3.progress * 0.0125f, sUni.progress.toFloat())
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

        listOf(s1, s2, s3, sUni).forEach { it.setOnSeekBarChangeListener(listener) }


        btnRot.setOnClickListener {
            toggleMode("ROTATE")
            sUni.visibility = View.GONE
            lUni.visibility = View.GONE
        }
        btnPos.setOnClickListener {
            toggleMode("POSITION")
            sUni.visibility = View.GONE
            lUni.visibility = View.GONE
        }
        btnScl.setOnClickListener {
            toggleMode("SCALE")
            sUni.visibility = View.VISIBLE
            lUni.visibility = View.VISIBLE

        }

        btnSave.setOnClickListener {
            onSaveRequested?.invoke()
        }
        btnDelete.setOnClickListener {
            onDeleteRequested?.invoke()
        }
    }
}