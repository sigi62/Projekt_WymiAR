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

    var onSaveRequested: (() -> Unit)? = null // Callback for the Activity

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

    private fun setupUI() {
        val s1 = findViewById<SeekBar>(R.id.seek1)
        val s2 = findViewById<SeekBar>(R.id.seek2)
        val s3 = findViewById<SeekBar>(R.id.seek3)
        val sUni = findViewById<SeekBar>(R.id.seekUniversalScale)
        val lUni = findViewById<TextView>(R.id.labelUniversalScale)

        val MIDDLE = 100
        val RANGE_MAX = 200

        // Reset all sliders to middle on start
        listOf(s1, s2, s3, sUni).forEach {
            it.max = RANGE_MAX
            it.progress = MIDDLE
        }

        val btnRot = findViewById<Button>(R.id.btnModeRotate)
        val btnPos = findViewById<Button>(R.id.btnModePosition)
        val btnScl = findViewById<Button>(R.id.btnModeScale)
        val btnSave = findViewById<Button>(R.id.btnSaveProfile)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                targetNode?.let { node ->
                    when (currentMode) {
                        "ROTATE" -> {
                            // Range: 0..200 -> -180..180 (factor 1.8)
                            val f = 1.8f
                            node.updateRotation(
                                (s1.progress - MIDDLE) * f,
                                (s2.progress - MIDDLE) * f,
                                (s3.progress - MIDDLE) * f
                            )
                        }

                        "POSITION" -> {
                            // Range: 0..200 -> -5m..5m (factor 0.05)
                            val f = 0.05f
                            node.updatePosition(
                                (s1.progress - MIDDLE) * f,
                                (s2.progress - MIDDLE) * f,
                                (s3.progress - MIDDLE) * f
                            )
                        }

                        "SCALE" -> {
                            // Range: 0..200 -> 0x..2.5x (factor 0.0125)
                            // Middle (100) results in 1.25x? No, let's use 0.01 to make 100 = 1.0x
                            // To get 2.5x max at progress 200, factor = 2.5 / 200 = 0.0125
                            val f = 0.0125f
                            node.updateScale(
                                s1.progress * f,
                                s2.progress * f,
                                s3.progress * f,
                                sUni.progress.toFloat() // node handles /100
                            )
                        }
                    }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

        listOf(s1, s2, s3, sUni).forEach { it.setOnSeekBarChangeListener(listener) }

        btnRot.setOnClickListener {
            currentMode = "ROTATE"
            sUni.visibility = View.GONE
            lUni.visibility = View.GONE
        }
        btnPos.setOnClickListener {
            currentMode = "POSITION"
            sUni.visibility = View.GONE
            lUni.visibility = View.GONE
        }
        btnScl.setOnClickListener {
            currentMode = "SCALE"
            sUni.visibility = View.VISIBLE
            lUni.visibility = View.VISIBLE

        }

        btnSave.setOnClickListener {
            onSaveRequested?.invoke()
        }
    }
}