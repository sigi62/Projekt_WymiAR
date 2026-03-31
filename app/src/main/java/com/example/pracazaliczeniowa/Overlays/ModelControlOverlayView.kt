package com.example.pracazaliczeniowa.Overlays

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode
import com.example.pracazaliczeniowa.R
import kotlin.math.abs

fun log(msg: String) {
    Log.d("AR_CONTROL_DEBUG", msg)
}

class ModelControlOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var targetNode: SelectedModelNode? = null
    private var currentMode = "ROTATE"

    // --- State Storage ---
    private var posMin = 0; private var posMax = 200;  private var posMid = 100
    private var sclMin = 0; private var sclMax = 100; private var sclMid = 10
    private val rotMin = 0; private val rotMax = 360; private var rotMid = 180
    private var positionProgress = Triple(posMid, posMid, posMid)
    private var scaleProgress = Triple(sclMid, sclMid, sclMid)
    private var rotateProgress = Triple(rotMid, rotMid, rotMid)
    private var universalScaleProgress = sclMid


    // prevention
    private val MIN_SCALE_VALUE = 0.1f

    var onSaveRequested: (() -> Unit)? = null // Callback for the Activity
    var onDeleteRequested: (() -> Unit)? = null // Callback for the Activity


    private lateinit var s1: SeekBar; private lateinit var s2: SeekBar; private lateinit var s3: SeekBar
    private lateinit var i1: EditText; private lateinit var i2: EditText; private lateinit var i3: EditText

    private lateinit var sUni: SeekBar; private lateinit var iUni: EditText

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
        // If factor > 1, we are pinching out (growing). If < 1, pinching in.
        // We adjust our Int-based progress based on the gesture's scale factor.
        val sensitivity = 10f
        val delta = (factor - 1.0f) * sensitivity

        val newProgress = (universalScaleProgress + delta.toInt()).coerceIn(sclMin, sclMax)

        universalScaleProgress = newProgress


        if (currentMode == "SCALE") {
            sUni.progress = newProgress
            // This will trigger the SeekBar listener, which calls applyToNode()
        } else {
            // If menu is closed, apply directly
            applyToNode()
        }
    }

    fun updateRotationFromHandle(yDegrees: Float) {
        // 1. Reverse the math: (degrees / 1.8) + MIDDLE
        // We normalize the degree to be within a standard -180 to 180 range first
        val normalizedDeg = ((yDegrees + 180) % 360) - 180
        val newProgress = (normalizedDeg / 1.8f + rotMid).toInt().coerceIn(rotMin, rotMax)

        // 2. Update state storage
        // We keep X and Z from the previous state, only updating Y (the second value)
        rotateProgress = Triple(rotateProgress.first, newProgress, rotateProgress.third)

        // 3. Update UI if currently in Rotate mode
        if (currentMode == "ROTATE") {
            val s2 = findViewById<SeekBar>(R.id.seek2) // Assuming seek2 is Y-axis
            s2.progress = newProgress
        }
    }

    fun updatePositionFromGeasture(x: Float, y: Float, z: Float) {
      //TODO
    }

    private fun setupUI() {

        val modeLabel = findViewById<TextView>(R.id.modeLabel)
        val seekBarsLayout = findViewById<LinearLayout>(R.id.seekBarsLayout)

        s1 = findViewById(R.id.seek1); s2 = findViewById(R.id.seek2); s3 = findViewById(R.id.seek3)
        i1 = findViewById(R.id.input1); i2 = findViewById(R.id.input2); i3 = findViewById(R.id.input3)
        sUni = findViewById(R.id.seekUniversalScale); iUni = findViewById(R.id.inputUniScale);  val lUni = findViewById<TextView>(R.id.labelUniversalScale)

        seekBarsLayout.visibility = View.GONE

        listOf(s1, s2, s3, sUni).forEach {
            it.max = getSeekBarMax()
            it.progress = getSeekBarMiddle()
        }


        val btnRot = findViewById<Button>(R.id.btnModeRotate)
        val btnPos = findViewById<Button>(R.id.btnModePosition)
        val btnScl = findViewById<Button>(R.id.btnModeScale)
        val btnSave = findViewById<Button>(R.id.btnModelSaveProfile)
        val btnDelete = findViewById<Button>(R.id.btnModelDelete)

        fun refreshSlidersForMode() {

            // 1. Update the physical limits of the sliders
            listOf(s1, s2, s3, sUni).forEach {
                it.max = getSeekBarMax()
                it.progress = getSeekBarMiddle()
            }

            log(  "seekbar middle : ")
            log(  getSeekBarMiddle().toString())
            val (p1, p2, p3) = when (currentMode) {
                "ROTATE" -> rotateProgress
                "POSITION" -> positionProgress
                "SCALE" -> (scaleProgress  )
                else -> Triple(0, 0, 0)
            }


            log(  "updating seekbar progress for every  : ")
            log(  p1.toString())
            log(  p2.toString())
            log(  p3.toString())

            s1.progress = p1
            s2.progress = p2
            s3.progress = p3
            sUni.progress = universalScaleProgress

            // 4. ADD THIS: Set initial text based on the progress values
            // Use the translator function progressToValue() created in the previous step
            i1.setText(String.format("%.1f", progressToValue(p1)))
            i2.setText(String.format("%.1f", progressToValue(p2)))
            i3.setText(String.format("%.1f", progressToValue(p3)))

            if (currentMode == "SCALE") {
                iUni.setText(String.format("%.1f", progressToValue(universalScaleProgress)))
            }
        }

        fun toggleMode(mode: String) {
            if (currentMode == mode && seekBarsLayout.visibility == View.VISIBLE) {
                // If clicking the same button again, hide the panel
                seekBarsLayout.visibility = View.GONE
                modeLabel.visibility = View.GONE
            } else {
                // Show panel and set the mode
                currentMode = mode
                seekBarsLayout.visibility = View.VISIBLE
                modeLabel.visibility = View.VISIBLE
                modeLabel.text = currentMode

                refreshSlidersForMode()

                // Toggle universal scale visibility based on mode
                val isScale = mode == "SCALE"
                sUni.visibility = if (isScale) View.VISIBLE else View.GONE
                lUni.visibility = if (isScale) View.VISIBLE else View.GONE
            }
        }

        //button mode change listener
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return

                // 1. Synchronize Universal Scale if in Scale Mode
                if (sb?.id == R.id.seekUniversalScale && currentMode == "SCALE") {
                    s1.progress = p; s2.progress = p; s3.progress = p
                    universalScaleProgress = p
                }

                // 2. Update State Storage
                when (currentMode) {
                    "ROTATE" -> rotateProgress = Triple(s1.progress, s2.progress, s3.progress)
                    "POSITION" -> positionProgress = Triple(s1.progress, s2.progress, s3.progress)
                    "SCALE" -> scaleProgress = Triple(s1.progress, s2.progress, s3.progress)
                }

                // 3. Update EditTexts based on the new logic
                val factor = if (currentMode == "ROTATE") 1f else 10f
                i1.setText(String.format("%.1f", s1.progress / factor))
                i2.setText(String.format("%.1f", s2.progress / factor))
                i3.setText(String.format("%.1f", s3.progress / factor))

                // 4. Finally, apply to the actual 3D Node
                applyToNode()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

        listOf(s1, s2, s3, sUni).forEach { it.setOnSeekBarChangeListener(seekListener) }


        // slider / text listeners
        val textWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val val1 = i1.text.toString().toFloatOrNull() ?: return
                val val2 = i2.text.toString().toFloatOrNull() ?: return
                val val3 = i3.text.toString().toFloatOrNull() ?: return
                updateFromInput(val1, val2, val3)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        listOf(i1, i2, i3).forEach { it.addTextChangedListener(textWatcher) }

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

    private fun updateFromInput(v1: Float, v2: Float, v3: Float) {
        val (prog1, prog2, prog3) = when (currentMode) {
            "POSITION" -> {
                // Float meters to Int: 5.5m -> 55
                Triple((v1 * 10).toInt(), (v2 * 10).toInt(), (v3 * 10).toInt())
            }
            "SCALE" -> {
                // Float scale to Int: 1.5x -> 15
                Triple((v1 * 10).toInt(), (v2 * 10).toInt(), (v3 * 10).toInt())
            }
            else -> { // ROTATE
                Triple(v1.toInt(), v2.toInt(), v3.toInt())
            }
        }

        // Update the SeekBars (this will trigger their listeners and call applyToNode)
        s1.progress = prog1.coerceIn(getMin(), getMax())
        s2.progress = prog2.coerceIn(getMin(), getMax())
        s3.progress = prog3.coerceIn(getMin(), getMax())
    }

    private fun applyToNode() {
        val node = targetNode ?: return

        // Get the current progress values from the sliders
        val p1 = s1.progress
        val p2 = s2.progress
        val p3 = s3.progress

        when (currentMode) {
            "POSITION" -> {
                // Philosophy: -100 to 100 Int = -10.0m to 10.0m Float (Divide by 10)
                node.updatePosition(p1 / 10f, p2 / 10f, p3 / 10f)
            }
            "ROTATE" -> {
                // Philosophy: -180 to 180 Int = -180.0 to 180.0 Degrees (Direct map)
                node.updateRotation(p1.toFloat(), p2.toFloat(), p3.toFloat())
            }
            "SCALE" -> {
                // Philosophy: 1 to 100 Int = 0.1 to 10.0 Scale (Divide by 10)
                node.updateScale((p1 / 10f).coerceAtLeast(MIN_SCALE_VALUE)
                    , (p2 / 10f).coerceAtLeast(MIN_SCALE_VALUE)
                    , (p3 / 10f).coerceAtLeast(MIN_SCALE_VALUE)
                    , (sUni.progress / 10f).coerceAtLeast(MIN_SCALE_VALUE))
            }
        }
    }
    private fun getMin() = when(currentMode) { "ROTATE" -> rotMin; "POSITION" -> posMin; else -> sclMin }
    private fun getMax() = when(currentMode) { "ROTATE" -> rotMax; "POSITION" -> posMax; else -> sclMax }


    private fun getSeekBarMiddle() = when(currentMode) { "ROTATE" -> 180; "POSITION" -> (abs(posMid) + abs(posMax))/2; else -> sclMid}
    private fun getSeekBarMax() = when(currentMode) { "ROTATE" -> 360 ; "POSITION" ->  abs(posMid) + abs(posMax); else -> sclMax}


    private fun progressToValue(progress: Int): Float {
        return when (currentMode) {
            "ROTATE" -> progress.toFloat() - 180f        // 0..360 -> -180..180
            "POSITION" -> (progress - 100f) / 10f      // 0..200 -> -10.0..10.0
            "SCALE" -> (progress / 10f).coerceAtLeast(MIN_SCALE_VALUE) // 1..100 -> 0.1..10.0
            else -> progress.toFloat()
        }
    }

    private fun valueToProgress(value: Float): Int {
        return when (currentMode) {
            "ROTATE" -> (value + 180).toInt().coerceIn(rotMin, rotMax)
            "POSITION" -> (value * 10 + 100).toInt().coerceIn(posMin, posMax)
            "SCALE" -> (value * 10).toInt().coerceIn(sclMin, sclMax)
            else -> value.toInt()
        }
    }

    private fun mapValueToProgress(value: Float, min: Float, max: Float): Int {
        val range = max - min
        if (range == 0f) return 500
        val percent = (value - min) / range
        return (percent * 1000).toInt().coerceIn(0, 1000)
    }

    private fun mapProgressToValue(progress: Int, min: Float, max: Float): Float {
        return min + (progress / 1000f) * (max - min)
    }
}