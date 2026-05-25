package com.example.pracazaliczeniowa.Overlays

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.example.pracazaliczeniowa.Helpers.RulerSeekBar
import com.example.pracazaliczeniowa.Objects.AppSettings
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode
import com.example.pracazaliczeniowa.R
import com.example.pracazaliczeniowa.Objects.DistanceUnit
import dev.romainguy.kotlin.math.Float3

fun log(msg: String) {
    Log.d("AR_CONTROL_DEBUG", msg)
}

class ModelControlOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private companion object {
        const val ROT_MIN = 0;  const val ROT_MAX = 3600;  const val ROT_MID = 1800
        const val SCL_MID = 100
        const val SCL_MIN = 1
        const val SCL_MAX_DEFAULT = 500
        const val SCL_MAX_HARD    = 10_000
        const val POS_MIN = 0
        const val POS_MID_DEFAULT = 100
        const val POS_MID_HARD    = 10_000

        const val MIN_SCALE_VALUE = 0.01f

    }

    private var targetNode: SelectedModelNode? = null
    private var currentMode = "ROTATE"
    private var posDynMid: Int = POS_MID_DEFAULT
    private var sclDynMax: Int = SCL_MAX_DEFAULT
    private var positionProgress       = Triple(posDynMid, posDynMid, posDynMid)
    private var scaleProgress          = Triple(SCL_MID,   SCL_MID,   SCL_MID)
    private var rotateProgress         = Triple(ROT_MID,   ROT_MID,   ROT_MID)
    private var universalScaleProgress = SCL_MID
    private var isSyncing = false

    private inline fun withSync(block: () -> Unit) {
        isSyncing = true
        try { block() } finally { isSyncing = false }
    }

    var onSaveRequested:   (() -> Unit)? = null
    var onDeleteRequested: (() -> Unit)? = null
    var onUnitChanged: ((DistanceUnit) -> Unit)? = null
    var onRelativeValuesChanged: ((scaleX: Float, scaleY: Float, scaleZ: Float, rotX: Float, rotY: Float, rotZ: Float) -> Unit)? = null


    private lateinit var unitToggleBtn: Button
    private lateinit var s1: RulerSeekBar;   private lateinit var s2: RulerSeekBar;   private lateinit var s3: RulerSeekBar
    private lateinit var i1: EditText;  private lateinit var i2: EditText;  private lateinit var i3: EditText
    private lateinit var sUni: RulerSeekBar; private lateinit var iUni: EditText


    init {
        LayoutInflater.from(context).inflate(R.layout.view_model_controls, this, true)
        orientation = VERTICAL
        isClickable  = true
        isFocusable  = true
    }


    fun bindToNode(node: SelectedModelNode) {
        if (::i1.isInitialized) { }
        targetNode = node

        val wrapped = node.getWrappedNode()
        if (wrapped != null) {
            val sx = wrapped.scale.x
            val sy = wrapped.scale.y
            val sz = wrapped.scale.z
            val rx = wrapped.rotation.x
            val ry = wrapped.rotation.y
            val rz = wrapped.rotation.z

            val savedMode = currentMode

            currentMode = "SCALE"
            val psx = valueToProgress(sx); val psy = valueToProgress(sy); val psz = valueToProgress(sz)
            val pUni = valueToProgress((sx + sy + sz) / 3f).coerceIn(SCL_MIN, sclDynMax)
            scaleProgress = Triple(psx, psy, psz)
            universalScaleProgress = pUni

            currentMode = "ROTATE"
            val prx = valueToProgress(rx); val pry = valueToProgress(ry); val prz = valueToProgress(rz)
            rotateProgress = Triple(prx, pry, prz)

            currentMode = savedMode
        }

        positionProgress = Triple(posDynMid, posDynMid, posDynMid)

        if (::s1.isInitialized) {
            val seekBarsLayout = findViewById<LinearLayout>(R.id.seekBarsLayout)
            val modeLabel      = findViewById<TextView>(R.id.modeLabel)
            val layoutUni      = findViewById<LinearLayout>(R.id.layoutUniversalScale)
            if (seekBarsLayout.visibility == View.VISIBLE) {
                val stringResId = when (currentMode) {
                    "ROTATE"   -> R.string.label_mode_rotate
                    "POSITION" -> R.string.label_mode_position
                    "SCALE"    -> R.string.label_mode_scale
                    else       -> 0
                }
                modeLabel.text       = if (stringResId != 0) context.getString(stringResId) else currentMode
                layoutUni.visibility = if (currentMode == "SCALE") View.VISIBLE else View.GONE
            }
            refreshSlidersForMode()
        } else {
            setupUI()
        }
    }

    fun bindToNodeWithRelativeValues(node: SelectedModelNode, relX: Float, relY: Float, relZ: Float, relativeRotation: Float3 = Float3(0f)) {
        targetNode = node

        val savedMode = currentMode

        currentMode = "SCALE"
        scaleProgress = Triple(valueToProgress(relX), valueToProgress(relY), valueToProgress(relZ))
        universalScaleProgress = valueToProgress((relX + relY + relZ) / 3f).coerceIn(SCL_MIN, sclDynMax)


        currentMode = "ROTATE"
        rotateProgress = Triple(
            valueToProgress(relativeRotation.x),
            valueToProgress(relativeRotation.y),
            valueToProgress(relativeRotation.z)
        )

        currentMode = savedMode   // restore to default mode
        positionProgress = Triple(posDynMid, posDynMid, posDynMid)

        if (::s1.isInitialized) {
            refreshSlidersForMode()
        } else {
            setupUI()
        }
    }
    fun resetSlidersToNeutral() {
        scaleProgress          = Triple(SCL_MID, SCL_MID, SCL_MID)
        universalScaleProgress = SCL_MID
        rotateProgress         = Triple(ROT_MID, ROT_MID, ROT_MID)
        positionProgress       = Triple(posDynMid, posDynMid, posDynMid)

        if (::s1.isInitialized) {
            refreshSlidersForMode()
        }
    }


    fun applySettings(settings: AppSettings) {
        val newPosMid = settings.posMidDefault
        val newSclMax = settings.sclMaxDefault

        if (newPosMid > posDynMid) {
            posDynMid = newPosMid
            positionProgress = Triple(posDynMid, posDynMid, posDynMid)
        }
        if (newSclMax > sclDynMax) {
            sclDynMax = newSclMax
        }

        currentUnitFactor = when (settings.distanceUnit) {
            DistanceUnit.METERS      -> 1f
            DistanceUnit.CENTIMETERS -> 100f
            DistanceUnit.MILLIMETERS -> 1000f
        }

        updateUnit(settings.distanceUnit)

        if (::s1.isInitialized) {
            withSync {
                val dynMax = modeMax()
                s1.max = dynMax; s2.max = dynMax; s3.max = dynMax
                sUni.max = sclDynMax
            }
            updateRulerVisuals()
        }
    }

    fun updateScaleFromGesture(factor: Float) {
        val sensitivity = 10f
        val delta = ((factor - 1.0f) * sensitivity).toInt()
        val newProgress = (universalScaleProgress + delta).coerceIn(SCL_MIN, sclDynMax)
        universalScaleProgress = newProgress

        scaleProgress = Triple(newProgress, newProgress, newProgress)

        if (currentMode == "SCALE") {
            withSync {
                sUni.progress = newProgress
                iUni.setText(formatValue(progressToValue(newProgress)))
                s1.progress = newProgress
                s2.progress = newProgress
                s3.progress = newProgress
                i1.setText(formatValue(progressToValue(newProgress)))
                i2.setText(formatValue(progressToValue(newProgress)))
                i3.setText(formatValue(progressToValue(newProgress)))
            }
        }
        applyToNode()
    }

    fun updateRotationFromHandle(yDegrees: Float) {
        val normalised = ((yDegrees + 180f) % 360f) - 180f
        val newProgress = (normalised * 10f + ROT_MID).toInt().coerceIn(ROT_MIN, ROT_MAX)
        rotateProgress = rotateProgress.copy(second = newProgress)

        if (currentMode == "ROTATE") {
            withSync {
                s2.progress = newProgress
                i2.setText(formatValue(progressToValue(newProgress)))
            }
        }
    }

    fun updatePositionFromGesture(x: Float, y: Float, z: Float) {
        // TODO
    }


    private fun setupUI() {
        val modeLabelRow   = findViewById<View>(R.id.modeLabelRow)
        val modeLabel      = findViewById<TextView>(R.id.modeLabel)
        val seekBarsLayout = findViewById<LinearLayout>(R.id.seekBarsLayout)
        val layoutUni      = findViewById<LinearLayout>(R.id.layoutUniversalScale)
        unitToggleBtn = findViewById(R.id.btnUnitToggle)


        s1   = findViewById(R.id.seek1);            s2   = findViewById(R.id.seek2)
        s3   = findViewById(R.id.seek3);            sUni = findViewById(R.id.seekUniversalScale)
        i1   = findViewById(R.id.input1);           i2   = findViewById(R.id.input2)
        i3   = findViewById(R.id.input3);           iUni = findViewById(R.id.inputUniScale)

        seekBarsLayout.visibility = View.GONE
        modeLabelRow.visibility      = View.GONE

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isSyncing) return

                withSync {
                    if (sb?.id == R.id.seekUniversalScale && currentMode == "SCALE") {
                        s1.progress = progress
                        s2.progress = progress
                        s3.progress = progress
                        universalScaleProgress = progress
                    }
                    saveCurrentProgress()
                    refreshEditTexts()
                }

                applyToNode()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

        listOf(s1, s2, s3, sUni).forEach { it.setOnSeekBarChangeListener(seekListener) }

        attachEditTextListeners(i1) { commitEditTexts() }
        attachEditTextListeners(i2) { commitEditTexts() }
        attachEditTextListeners(i3) { commitEditTexts() }
        attachEditTextListeners(iUni) { commitUniScaleEditText() }


        unitToggleBtn.setOnClickListener {
            val newUnit = when (currentUnitFactor) {
                1f    -> DistanceUnit.CENTIMETERS
                100f  -> DistanceUnit.MILLIMETERS
                else  -> DistanceUnit.METERS
            }
            updateUnit(newUnit)
            onUnitChanged?.invoke(newUnit)
            refreshUnitButton()
        }

        fun toggleMode(mode: String) {
            if (currentMode == mode && seekBarsLayout.visibility == View.VISIBLE) {
                seekBarsLayout.visibility = View.GONE
                modeLabelRow.visibility      = View.GONE
            } else {
                currentMode = mode
                seekBarsLayout.visibility = View.VISIBLE
                modeLabelRow.visibility      = View.VISIBLE

                val stringResId = when (mode) {
                    "ROTATE"   -> R.string.label_mode_rotate
                    "POSITION" -> R.string.label_mode_position
                    "SCALE"    -> R.string.label_mode_scale
                    else       -> 0
                }
                if (stringResId != 0) {
                    modeLabel.text = context.getString(stringResId)
                } else {
                    modeLabel.text = mode
                }

                layoutUni.visibility      = if (mode == "SCALE") View.VISIBLE else View.GONE
                unitToggleBtn.visibility  = if (mode == "POSITION") View.VISIBLE else View.GONE  // ← add


                refreshSlidersForMode()
            }
        }

        findViewById<Button>(R.id.btnModeRotate).setOnClickListener   { toggleMode("ROTATE")   }
        findViewById<Button>(R.id.btnModePosition).setOnClickListener { toggleMode("POSITION") }
        findViewById<Button>(R.id.btnModeScale).setOnClickListener    { toggleMode("SCALE")    }

        findViewById<Button>(R.id.btnModelSaveProfile).setOnClickListener { onSaveRequested?.invoke()  }
        findViewById<Button>(R.id.btnModelDelete).setOnClickListener      { onDeleteRequested?.invoke() }

        refreshUnitButton()
    }


    private fun attachEditTextListeners(editText: EditText, onCommit: () -> Unit) {

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                if (isSyncing) return@setOnFocusChangeListener
                onCommit()
                dismissKeyboard(editText)
            }
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onCommit()
                editText.clearFocus()
                dismissKeyboard(editText)
                true
            } else false
        }
    }

    private fun dismissKeyboard(view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }


    private fun commitEditTexts() {
        val v1 = i1.text.toString().toFloatOrNull() ?: progressToValue(s1.progress)
        val v2 = i2.text.toString().toFloatOrNull() ?: progressToValue(s2.progress)
        val v3 = i3.text.toString().toFloatOrNull() ?: progressToValue(s3.progress)

        maybeExpandRange(v1); maybeExpandRange(v2); maybeExpandRange(v3)

        val p1 = valueToProgress(v1)
        val p2 = valueToProgress(v2)
        val p3 = valueToProgress(v3)

        withSync {
            val dynMax = modeMax()
            s1.max = dynMax;  s2.max = dynMax;  s3.max = dynMax
            updateRulerVisuals()   // redraw ticks if range just expanded

            s1.progress = p1;  s2.progress = p2;  s3.progress = p3
            i1.setText(formatValue(progressToValue(p1)))
            i2.setText(formatValue(progressToValue(p2)))
            i3.setText(formatValue(progressToValue(p3)))
            i1.setSelection(i1.text.length)
            i2.setSelection(i2.text.length)
            i3.setSelection(i3.text.length)
            saveCurrentProgress()
        }

        log("Committed [$currentMode] values: $v1, $v2, $v3  →  progress: $p1, $p2, $p3 | posMid=$posDynMid sclMax=$sclDynMax")
        applyToNode()
    }

    private fun commitUniScaleEditText() {
        if (currentMode != "SCALE") return

        val v = iUni.text.toString().toFloatOrNull() ?: progressToValue(sUni.progress)
        maybeExpandRange(v)

        val p = valueToProgress(v).coerceIn(SCL_MIN, sclDynMax)
        universalScaleProgress = p

        withSync {
            sUni.max = sclDynMax
            s1.max   = sclDynMax;  s2.max = sclDynMax;  s3.max = sclDynMax
            updateRulerVisuals()

            sUni.progress = p
            s1.progress   = p;  s2.progress = p;  s3.progress = p
            iUni.setText(formatValue(progressToValue(p)))
            iUni.setSelection(iUni.text.length)

            i1.setText(formatValue(progressToValue(p)))
            i2.setText(formatValue(progressToValue(p)))
            i3.setText(formatValue(progressToValue(p)))
            saveCurrentProgress()
        }

        applyToNode()
    }

    private fun refreshSlidersForMode() {
        val (p1, p2, p3) = when (currentMode) {
            "ROTATE"   -> rotateProgress
            "POSITION" -> positionProgress
            "SCALE"    -> scaleProgress
            else       -> Triple(100, 100, 100)
        }

        withSync {
            val dynMax = modeMax()
            s1.max = dynMax;  s2.max = dynMax;  s3.max = dynMax
            sUni.max = sclDynMax

            updateRulerVisuals()

            s1.progress = p1;  s2.progress = p2;  s3.progress = p3
            sUni.progress = universalScaleProgress

            i1.setText(formatValue(progressToValue(p1)))
            i2.setText(formatValue(progressToValue(p2)))
            i3.setText(formatValue(progressToValue(p3)))
            if (currentMode == "SCALE") {
                iUni.setText(formatValue(progressToValue(universalScaleProgress)))
            }
        }
    }

    private fun refreshEditTexts() {
        i1.setText(formatValue(progressToValue(s1.progress)))
        i2.setText(formatValue(progressToValue(s2.progress)))
        i3.setText(formatValue(progressToValue(s3.progress)))
        if (currentMode == "SCALE") {
            iUni.setText(formatValue(progressToValue(sUni.progress)))
        }
    }

    private fun refreshUnitButton() {
        if (!::unitToggleBtn.isInitialized) return
        unitToggleBtn.text = when (currentUnitFactor) {
            1f    -> "m"
            100f  -> "cm"
            else  -> "mm"
        }
    }

    private fun saveCurrentProgress() {
        when (currentMode) {
            "ROTATE"   -> rotateProgress    = Triple(s1.progress, s2.progress, s3.progress)
            "POSITION" -> positionProgress  = Triple(s1.progress, s2.progress, s3.progress)
            "SCALE"    -> scaleProgress     = Triple(s1.progress, s2.progress, s3.progress)
        }
    }

    private fun applyToNode() {
        val node = targetNode ?: return
        val v1 = progressToValue(s1.progress)
        val v2 = progressToValue(s2.progress)
        val v3 = progressToValue(s3.progress)

        when (currentMode) {
            "POSITION" -> node.updatePosition(v1 / currentUnitFactor, v2 / currentUnitFactor, v3 / currentUnitFactor)
            "ROTATE"   -> {
                node.updateRotation(v1, v2, v3)
                onRelativeValuesChanged?.invoke(
                    progressToValue(scaleProgress.first),
                    progressToValue(scaleProgress.second),
                    progressToValue(scaleProgress.third),
                    v1, v2, v3
                )
            }
            "SCALE"    -> {
                node.updateScale(v1, v2, v3, progressToValue(sUni.progress))
                onRelativeValuesChanged?.invoke(
                    v1, v2, v3,
                    progressToValue(rotateProgress.first),
                    progressToValue(rotateProgress.second),
                    progressToValue(rotateProgress.third)
                )
            }
        }
    }

    private fun progressToValue(progress: Int): Float = when (currentMode) {
        "POSITION" -> (progress - posDynMid).toFloat()
        "ROTATE"   -> (progress - ROT_MID).toFloat() / 10f
        "SCALE"    -> (progress / SCL_MID.toFloat()).coerceAtLeast(MIN_SCALE_VALUE)
        else       -> progress.toFloat()
    }

    private fun valueToProgress(value: Float): Int = when (currentMode) {
        "POSITION" -> (value + posDynMid).toInt().coerceIn(POS_MIN, 2 * posDynMid)
        "ROTATE"   -> (value * 10f + ROT_MID).toInt().coerceIn(ROT_MIN, ROT_MAX)
        "SCALE"    -> (value * SCL_MID).toInt().coerceIn(SCL_MIN, sclDynMax)
        else       -> value.toInt()
    }

    private fun updateRulerVisuals() {
        if (!::s1.isInitialized) return
        val sliders = listOf(s1, s2, s3)
        when (currentMode) {
            "SCALE" -> {
                val scaleMax = sclDynMax / SCL_MID.toFloat()
                val minor = if (scaleMax > 20f) 1f else 0.2f
                sliders.plus(sUni).forEach {
                    it.updateRange(min = 0f, max = scaleMax, center = 1f, major = 1f, minor = minor)
                    it.decimalPlaces = 2
                }
            }
            "POSITION" -> {
                val posMax = posDynMid.toFloat()
                val major = (posMax / 2f).coerceAtLeast(1f)
                val minor = (posMax / 10f).coerceAtLeast(0.1f)
                sliders.forEach {
                    it.updateRange(min = -posMax, max = posMax, center = 0f, major = major, minor = minor)
                    it.setStepsFromRange()
                    it.decimalPlaces = 1
                }
            }
            "ROTATE" -> {
                sliders.forEach {
                    it.updateRange(min = -180f, max = 180f, center = 0f, major = 30f, minor = 10f)
                    it.decimalPlaces = 1
                }
            }
        }
    }

    private fun maybeExpandRange(value: Float) {
        var expanded = false
        when (currentMode) {
            "POSITION" -> {
                val valueCm = value / (currentUnitFactor / 100f)
                val needed = kotlin.math.abs(valueCm).toInt()
                if (needed > posDynMid) {
                    posDynMid = needed.coerceAtMost(POS_MID_HARD)
                    expanded = true
                }
            }
            "SCALE" -> {
                val neededMax = (value * SCL_MID).toInt()
                if (neededMax > sclDynMax) {
                    sclDynMax = neededMax.coerceAtMost(SCL_MAX_HARD)
                    expanded = true
                }
            }
        }
        if (expanded) updateRulerVisuals()
    }

    private fun modeMax() = when (currentMode) {
        "POSITION" -> 2 * posDynMid
        "ROTATE"   -> ROT_MAX
        else       -> sclDynMax
    }

    private fun formatValue(value: Float) = when (currentMode) {
        "SCALE"    -> "%.2f".format(value)
        else       -> "%.1f".format(value)
    }
    private var currentUnitFactor: Float = 100f
    fun updateUnit(unit: DistanceUnit) {
        val oldFactor  = currentUnitFactor
        currentUnitFactor = when (unit) {
            DistanceUnit.METERS      -> 1f
            DistanceUnit.CENTIMETERS -> 100f
            DistanceUnit.MILLIMETERS -> 1000f
        }

        val oldPosDynMid = posDynMid
        posDynMid = posDynMid.coerceAtLeast(POS_MID_DEFAULT)

        val unitMultiplier = currentUnitFactor / oldFactor
        positionProgress = Triple(
            (((positionProgress.first  - oldPosDynMid) * unitMultiplier) + posDynMid).toInt()
                .coerceIn(POS_MIN, 2 * posDynMid),
            (((positionProgress.second - oldPosDynMid) * unitMultiplier) + posDynMid).toInt()
                .coerceIn(POS_MIN, 2 * posDynMid),
            (((positionProgress.third  - oldPosDynMid) * unitMultiplier) + posDynMid).toInt()
                .coerceIn(POS_MIN, 2 * posDynMid)
        )

        if (::s1.isInitialized) {
            updateRulerVisuals()
            refreshSlidersForMode()
        }
        refreshUnitButton()
    }
}