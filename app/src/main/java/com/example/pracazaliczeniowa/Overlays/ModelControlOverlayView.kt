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
    private var sclMin = 1; private var sclMax = 500; private var sclMid = 100
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
        sUni = findViewById(R.id.seekUniversalScale); iUni = findViewById(R.id.inputUniScale);
        val layoutUni = findViewById<LinearLayout>(R.id.layoutUniversalScale);

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

        var isSyncing = false

        fun refreshSlidersForMode() {

            val modeMax = getSeekBarMax()
            isSyncing = true

            s1.max = modeMax; s2.max = modeMax; s3.max = modeMax; sUni.max = modeMax

            val (p1, p2, p3) = when (currentMode) {
                "ROTATE" -> rotateProgress
                "POSITION" -> positionProgress
                "SCALE" -> (scaleProgress  )
                else -> Triple(100, 100, 100)
            }

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
            isSyncing = false
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
                layoutUni.visibility = if (isScale) View.VISIBLE else View.GONE
            }
        }
        //button mode change listener
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (isSyncing) return

                isSyncing = true

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

                log("currentMode: $currentMode")
                log("s1 progress : ${s1.progress}")
                log("s1 progress to value : ${progressToValue(s1.progress)}")
                // 3. Update EditTexts based on the new logic
                // SYNC EDIT TEXTS: Progress -> Value
                i1.setText(String.format("%.1f", progressToValue(s1.progress)))
                i2.setText(String.format("%.1f", progressToValue(s2.progress)))
                i3.setText(String.format("%.1f", progressToValue(s3.progress)))
                if(currentMode == "SCALE") iUni.setText(String.format("%.1f", progressToValue(sUni.progress)))

                isSyncing = false
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
                if (isSyncing) return // Prevent infinite loops
                isSyncing = true

                val v1 = i1.text.toString().toFloatOrNull() ?: 0f
                val v2 = i2.text.toString().toFloatOrNull() ?: 0f
                val v3 = i3.text.toString().toFloatOrNull() ?: 0f

                // SYNC SEEKBARS: Value -> Progress
                s1.progress = valueToProgress(v1)
                s2.progress = valueToProgress(v2)
                s3.progress = valueToProgress(v3)

                // Note: Seekbar progress change will trigger applyToNode()
                isSyncing  = false
                applyToNode()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        listOf(i1, i2, i3).forEach { it.addTextChangedListener(textWatcher) }

        btnRot.setOnClickListener {
            toggleMode("ROTATE")
        }
        btnPos.setOnClickListener {
            toggleMode("POSITION")
        }
        btnScl.setOnClickListener {
            toggleMode("SCALE")

        }

        btnSave.setOnClickListener {
            onSaveRequested?.invoke()
        }
        btnDelete.setOnClickListener {
            onDeleteRequested?.invoke()
        }
    }

    private fun applyToNode() {
        val node = targetNode ?: return

        // Get the current progress values from the sliders
        val val1 = progressToValue(s1.progress)
        val val2 = progressToValue(s2.progress)
        val val3 = progressToValue(s3.progress)

        val measureUnitFactor = 100f

        when (currentMode) {
            "POSITION" -> node.updatePosition(val1 /measureUnitFactor, val2 /measureUnitFactor, val3 / measureUnitFactor)
            "ROTATE" -> node.updateRotation(val1, val2, val3)
            "SCALE" -> {
                val valUni = progressToValue(sUni.progress)
                node.updateScale(val1, val2, val3, valUni)
            }
        }
    }

    private fun getSeekBarMiddle() = when(currentMode) { "ROTATE" -> rotMid; "POSITION" -> posMid; else -> sclMid}
    private fun getSeekBarMax() = when(currentMode) { "ROTATE" -> rotMax ; "POSITION" ->  posMax; else -> sclMax}


    private fun progressToValue(progress: Int): Float {
        return when (currentMode) {
            "ROTATE" -> progress.toFloat() - rotMid        // 0..360 -> -180..180
            "POSITION" -> ((progress - posMid.toFloat()) )      // 0..200 -> -10.0..10.0
            "SCALE" -> (progress / sclMid.toFloat()).coerceAtLeast(MIN_SCALE_VALUE) // 1..1000 -> 0.01..10.0
            else -> progress.toFloat()
        }
    }

    private fun valueToProgress(value: Float): Int {
        return when (currentMode) {
            "ROTATE" -> (value + rotMid).toInt().coerceIn(rotMin, rotMax)
            "POSITION" -> ((value ) + posMid).toInt().coerceIn(posMin, posMax)
            "SCALE" -> (value * sclMid).toInt().coerceIn(sclMin, sclMax)
            else -> value.toInt()
        }
    }

    private fun setEditTextValues(p1: Int, p2: Int, p3: Int, pUni: Int?) {
         when (currentMode) {
            "ROTATE" -> {
                i1.setText(String.format("%f", progressToValue(p1)))
                i2.setText(String.format("%f", progressToValue(p2)))
                i3.setText(String.format("%f", progressToValue(p3)))
            }
            "POSITION" -> {
                i1.setText(String.format("%.1f", progressToValue(p1)))
                i2.setText(String.format("%.1f", progressToValue(p2)))
                i3.setText(String.format("%.1f", progressToValue(p3)))
            }
            "SCALE" -> {
                i1.setText(String.format("%.1f", progressToValue(p1)))
                i2.setText(String.format("%.1f", progressToValue(p2)))
                i3.setText(String.format("%.1f", progressToValue(p3)))
            }
            else -> {

            }
        }
    }

}