package com.example.pracazaliczeniowa

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.romainguy.kotlin.math.Float3

/**
 * Simple UI overlay with sliders to control the selected model's
 * uniform scale (in metres) and local position along X/Y/Z axes.
 *
 * This view is purely UI: it delegates all scene changes to
 * [MeasurableModelNode].
 */
class ModelControlOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var boundNode: MeasurableModelNode? = null

    private val scaleSeek: SeekBar
    private val xSeek: SeekBar
    private val ySeek: SeekBar
    private val zSeek: SeekBar

    private val scaleLabel: TextView
    private val xLabel: TextView
    private val yLabel: TextView
    private val zLabel: TextView

    // Reasonable ranges for demo:
    // Scale: 0.05 m .. 1.0 m
    // Position: -0.5 m .. +0.5 m for each axis
    private val scaleMin = 0.05f
    private val scaleMax = 1.0f
    private val posMin = -0.5f
    private val posMax = 0.5f

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_model_controls, this, true)

        scaleSeek = findViewById(R.id.seekScale)
        xSeek = findViewById(R.id.seekPosX)
        ySeek = findViewById(R.id.seekPosY)
        zSeek = findViewById(R.id.seekPosZ)

        scaleLabel = findViewById(R.id.labelScale)
        xLabel = findViewById(R.id.labelPosX)
        yLabel = findViewById(R.id.labelPosY)
        zLabel = findViewById(R.id.labelPosZ)

        setupSeekBars()
        visibility = View.GONE
    }

    private fun setupSeekBars() {
        // All seek bars use 0..100 and are mapped to float ranges.
        val max = 100
        scaleSeek.max = max
        xSeek.max = max
        ySeek.max = max
        zSeek.max = max

        fun SeekBar.onChange(block: (progress: Int, fromUser: Boolean) -> Unit) {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    block(progress, fromUser)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        scaleSeek.onChange { p, fromUser ->
            val node = boundNode ?: return@onChange
            val value = lerp(scaleMin, scaleMax, p / 100f)
            scaleLabel.text = String.format("Scale: %.2f m", value)
            if (fromUser) {
                node.setMetersScale(value)
            }
        }

        val posFormatter = { axis: String, v: Float ->
            String.format("%s: %.2f m", axis, v)
        }

        xSeek.onChange { p, fromUser ->
            val node = boundNode ?: return@onChange
            val value = lerp(posMin, posMax, p / 100f)
            val current = node.getLocalPosition()
            xLabel.text = posFormatter("X", value)
            if (fromUser) {
                node.setLocalPosition(value, current.y, current.z)
            }
        }

        ySeek.onChange { p, fromUser ->
            val node = boundNode ?: return@onChange
            val value = lerp(posMin, posMax, p / 100f)
            val current = node.getLocalPosition()
            yLabel.text = posFormatter("Y", value)
            if (fromUser) {
                node.setLocalPosition(current.x, value, current.z)
            }
        }

        zSeek.onChange { p, fromUser ->
            val node = boundNode ?: return@onChange
            val value = lerp(posMin, posMax, p / 100f)
            val current = node.getLocalPosition()
            zLabel.text = posFormatter("Z", value)
            if (fromUser) {
                node.setLocalPosition(current.x, current.y, value)
            }
        }
    }

    /**
     * Bind this control overlay to a specific [MeasurableModelNode].
     * The current node transform is read once to initialise sliders.
     */
    fun bindToNode(node: MeasurableModelNode) {
        boundNode = node
        visibility = View.VISIBLE

        // Initialise sliders from the node state.
        val pos = node.getLocalPosition()
        val s = node.currentScaleMeters()

        val scaleProgress = ((s - scaleMin) / (scaleMax - scaleMin)).coerceIn(0f, 1f) * 100f
        val xProgress = ((pos.x - posMin) / (posMax - posMin)).coerceIn(0f, 1f) * 100f
        val yProgress = ((pos.y - posMin) / (posMax - posMin)).coerceIn(0f, 1f) * 100f
        val zProgress = ((pos.z - posMin) / (posMax - posMin)).coerceIn(0f, 1f) * 100f

        scaleSeek.progress = scaleProgress.toInt()
        xSeek.progress = xProgress.toInt()
        ySeek.progress = yProgress.toInt()
        zSeek.progress = zProgress.toInt()

        scaleLabel.text = String.format("Scale: %.2f m", s)
        xLabel.text = String.format("X: %.2f m", pos.x)
        yLabel.text = String.format("Y: %.2f m", pos.y)
        zLabel.text = String.format("Z: %.2f m", pos.z)
    }

    /** Unbind the currently controlled node and hide the overlay. */
    fun unbind() {
        boundNode = null
        visibility = View.GONE
    }

    private fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t

    private fun MeasurableModelNode.currentScaleMeters(): Float =
        // widthMeters is 2 * halfX * _scale; we just want the internal scale.
        // Expose a dedicated getter instead of reverse-computing from width.
        getMetersScale()

    private fun MeasurableModelNode.getLocalPosition(): Float3 =
        localPosition()
}

