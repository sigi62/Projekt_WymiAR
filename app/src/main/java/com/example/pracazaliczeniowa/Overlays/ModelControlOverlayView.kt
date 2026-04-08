package com.example.pracazaliczeniowa.Overlays

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.example.pracazaliczeniowa.Helpers.AppSettings
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode
import com.example.pracazaliczeniowa.R

fun log(msg: String) {
    Log.d("AR_CONTROL_DEBUG", msg)
}

class ModelControlOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    // -------------------------------------------------------------------------
    // Configuration — fixed constants
    // -------------------------------------------------------------------------

    private companion object {
        // Rotation is fixed; never adapts.
        const val ROT_MIN = 0;  const val ROT_MAX = 360;  const val ROT_MID = 180

        // Scale: progress / SCL_MID = displayed value.  SCL_MID is fixed as the
        // denominator so the mapping stays simple; only the seekbar max grows.
        const val SCL_MID = 100
        const val SCL_MIN = 1
        // Default seekbar max  →  5.00×  (progress 500 / SCL_MID 100)
        const val SCL_MAX_DEFAULT = 500
        // Hard ceiling: 100.00×  (progress 10000 / SCL_MID 100)
        const val SCL_MAX_HARD    = 10_000

        // Position: progress - posMid = displayed cm value.  posMid grows when
        // the user types a value outside the current range.
        const val POS_MIN = 0
        // Default half-range → ±100 cm  (seekbar 0..200, mid=100)
        const val POS_MID_DEFAULT = 100
        // Hard ceiling: ±1000 cm
        const val POS_MID_HARD    = 1_000

        const val MIN_SCALE_VALUE = 0.01f

        /** Delay after the last keystroke before committing an EditText value (ms). */
        const val EDIT_COMMIT_DELAY_MS = 2000L
    }

    // -------------------------------------------------------------------------
    // State — dynamic range (mutable; grow on demand, never shrink)
    // -------------------------------------------------------------------------

    private var targetNode: SelectedModelNode? = null
    private var currentMode = "ROTATE"

    /**
     * Current half-range for position in cm.
     * Seekbar range is always  0 .. 2*posDynMid,  displayed value = progress - posDynMid.
     * Grows when the user types a value whose |abs| > current mid; capped at POS_MID_HARD.
     *
     * Initialised from [AppSettings] via [applySettings]; falls back to [POS_MID_DEFAULT].
     */
    private var posDynMid: Int = POS_MID_DEFAULT

    /**
     * Current seekbar max for scale.
     * Displayed value = progress / SCL_MID.
     * Grows when the user types a value whose progress equivalent > current max;
     * capped at SCL_MAX_HARD.
     *
     * Initialised from [AppSettings] via [applySettings]; falls back to [SCL_MAX_DEFAULT].
     */
    private var sclDynMax: Int = SCL_MAX_DEFAULT

    /** Persisted progress values per mode so switching tabs restores sliders. */
    private var positionProgress       = Triple(posDynMid, posDynMid, posDynMid)
    private var scaleProgress          = Triple(SCL_MID,   SCL_MID,   SCL_MID)
    private var rotateProgress         = Triple(ROT_MID,   ROT_MID,   ROT_MID)
    private var universalScaleProgress = SCL_MID

    // -------------------------------------------------------------------------
    // Sync guard — prevents seekbar ↔ editText feedback loops
    // -------------------------------------------------------------------------

    private var isSyncing = false

    private inline fun withSync(block: () -> Unit) {
        isSyncing = true
        try { block() } finally { isSyncing = false }
    }

    // -------------------------------------------------------------------------
    // Debounce infrastructure for EditText commit
    // -------------------------------------------------------------------------

    private val commitHandler = Handler(Looper.getMainLooper())

    private val pendingCommit = HashMap<EditText, Runnable>()

    private fun scheduleCommit(editText: EditText, action: () -> Unit) {
        pendingCommit[editText]?.let { commitHandler.removeCallbacks(it) }
        val runnable = Runnable { action() }
        pendingCommit[editText] = runnable
        commitHandler.postDelayed(runnable, EDIT_COMMIT_DELAY_MS)
    }

    private fun cancelCommit(editText: EditText) {
        pendingCommit.remove(editText)?.let { commitHandler.removeCallbacks(it) }
    }

    // -------------------------------------------------------------------------
    // Callbacks for the host Activity
    // -------------------------------------------------------------------------

    var onSaveRequested:   (() -> Unit)? = null
    var onDeleteRequested: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // Views (lateinit — bound after inflation)
    // -------------------------------------------------------------------------

    private lateinit var s1: SeekBar;   private lateinit var s2: SeekBar;   private lateinit var s3: SeekBar
    private lateinit var i1: EditText;  private lateinit var i2: EditText;  private lateinit var i3: EditText
    private lateinit var sUni: SeekBar; private lateinit var iUni: EditText

    // -------------------------------------------------------------------------
    // Init
    // -------------------------------------------------------------------------

    init {
        LayoutInflater.from(context).inflate(R.layout.view_model_controls, this, true)
        orientation = VERTICAL
        isClickable  = true
        isFocusable  = true
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun bindToNode(node: SelectedModelNode) {
        targetNode = node
        setupUI()
    }

    /**
     * Apply persisted settings from [AppSettings] as the new starting defaults
     * for [posDynMid] and [sclDynMax].
     *
     * Call this:
     *  • Once in [ARActivity.onCreate] before any node is bound, so the very
     *    first seekbars already reflect the user's chosen ranges.
     *  • Again in [ARActivity.onResume] so a change made in SettingsActivity
     *    is picked up without needing to restart the app.
     *
     * The method only *lowers* or *raises* the defaults; it never shrinks a
     * range that was already dynamically expanded during the current session
     * (we keep the "never shrink" contract).
     */
    fun applySettings(settings: AppSettings) {
        val newPosMid = settings.posMidDefault
        val newSclMax = settings.sclMaxDefault

        // Only update if the stored value is larger than the current dynamic
        // value (i.e. don't shrink a range the user already expanded via typing).
        if (newPosMid > posDynMid) {
            posDynMid = newPosMid
            // Reset position progress to the new centre so sliders start neutral.
            positionProgress = Triple(posDynMid, posDynMid, posDynMid)
            log("Settings applied → posDynMid=$posDynMid")
        }
        if (newSclMax > sclDynMax) {
            sclDynMax = newSclMax
            log("Settings applied → sclDynMax=$sclDynMax")
        }

        // If the UI is already inflated, refresh the seekbar maxes immediately.
        if (::s1.isInitialized) {
            withSync {
                val dynMax = modeMax()
                s1.max = dynMax;  s2.max = dynMax;  s3.max = dynMax
                sUni.max = sclDynMax
            }
        }
    }

    /** Called by a pinch gesture; updates the universal-scale progress and applies it. */
    fun updateScaleFromGesture(factor: Float) {
        val sensitivity = 10f
        val delta = ((factor - 1.0f) * sensitivity).toInt()
        val newProgress = (universalScaleProgress + delta).coerceIn(SCL_MIN, sclDynMax)
        universalScaleProgress = newProgress

        if (currentMode == "SCALE") {
            withSync {
                sUni.progress = newProgress
                iUni.setText(formatValue(progressToValue(newProgress)))
            }
        }
        applyToNode()
    }

    /** Called by a drag-handle gesture; overrides only the Y rotation. */
    fun updateRotationFromHandle(yDegrees: Float) {
        val normalised = ((yDegrees + 180) % 360) - 180
        val newProgress = (normalised / 1.8f + ROT_MID).toInt().coerceIn(ROT_MIN, ROT_MAX)
        rotateProgress = rotateProgress.copy(second = newProgress)

        if (currentMode == "ROTATE") {
            withSync {
                s2.progress = newProgress
                i2.setText(formatValue(progressToValue(newProgress)))
            }
        }
    }

    /** Stub – implement when gesture position tracking is ready. */
    fun updatePositionFromGesture(x: Float, y: Float, z: Float) {
        // TODO
    }

    // -------------------------------------------------------------------------
    // UI setup (called once after inflation + node binding)
    // -------------------------------------------------------------------------

    private fun setupUI() {
        val modeLabel      = findViewById<TextView>(R.id.modeLabel)
        val seekBarsLayout = findViewById<LinearLayout>(R.id.seekBarsLayout)
        val layoutUni      = findViewById<LinearLayout>(R.id.layoutUniversalScale)

        s1   = findViewById(R.id.seek1);            s2   = findViewById(R.id.seek2)
        s3   = findViewById(R.id.seek3);            sUni = findViewById(R.id.seekUniversalScale)
        i1   = findViewById(R.id.input1);           i2   = findViewById(R.id.input2)
        i3   = findViewById(R.id.input3);           iUni = findViewById(R.id.inputUniScale)

        seekBarsLayout.visibility = View.GONE
        modeLabel.visibility      = View.GONE

        // ------------------------------------------------------------------
        // SeekBar listener — single shared instance for s1/s2/s3/sUni
        // ------------------------------------------------------------------

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || isSyncing) return

                withSync {
                    // Universal scale drives X/Y/Z when in Scale mode
                    if (sb?.id == R.id.seekUniversalScale && currentMode == "SCALE") {
                        s1.progress = progress
                        s2.progress = progress
                        s3.progress = progress
                        universalScaleProgress = progress
                    }

                    // Persist progress for the active mode
                    saveCurrentProgress()

                    // Reflect new values in EditTexts
                    refreshEditTexts()
                }

                applyToNode()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

        listOf(s1, s2, s3, sUni).forEach { it.setOnSeekBarChangeListener(seekListener) }

        // ------------------------------------------------------------------
        // EditText listeners — debounced commit + immediate-on-focus-lost
        // ------------------------------------------------------------------

        attachEditTextListeners(i1) { commitEditTexts() }
        attachEditTextListeners(i2) { commitEditTexts() }
        attachEditTextListeners(i3) { commitEditTexts() }
        attachEditTextListeners(iUni) { commitUniScaleEditText() }

        // ------------------------------------------------------------------
        // Mode buttons
        // ------------------------------------------------------------------

        fun toggleMode(mode: String) {
            if (currentMode == mode && seekBarsLayout.visibility == View.VISIBLE) {
                seekBarsLayout.visibility = View.GONE
                modeLabel.visibility      = View.GONE
            } else {
                currentMode = mode
                seekBarsLayout.visibility = View.VISIBLE
                modeLabel.visibility      = View.VISIBLE
                modeLabel.text            = currentMode
                layoutUni.visibility      = if (mode == "SCALE") View.VISIBLE else View.GONE

                refreshSlidersForMode()
            }
        }

        findViewById<Button>(R.id.btnModeRotate).setOnClickListener   { toggleMode("ROTATE")   }
        findViewById<Button>(R.id.btnModePosition).setOnClickListener { toggleMode("POSITION") }
        findViewById<Button>(R.id.btnModeScale).setOnClickListener    { toggleMode("SCALE")    }

        findViewById<Button>(R.id.btnModelSaveProfile).setOnClickListener { onSaveRequested?.invoke()  }
        findViewById<Button>(R.id.btnModelDelete).setOnClickListener      { onDeleteRequested?.invoke() }
    }

    // -------------------------------------------------------------------------
    // EditText wiring helper
    // -------------------------------------------------------------------------

    private fun attachEditTextListeners(editText: EditText, onCommit: () -> Unit) {
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isSyncing) return
                scheduleCommit(editText, onCommit)
            }
        })

        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                cancelCommit(editText)
                onCommit()
                dismissKeyboard(editText)
            }
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                cancelCommit(editText)
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

    // -------------------------------------------------------------------------
    // Commit helpers — parse EditTexts, clamp, push to seekbars, apply
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Refresh helpers
    // -------------------------------------------------------------------------

    private fun refreshSlidersForMode() {
        val (p1, p2, p3) = when (currentMode) {
            "ROTATE"   -> rotateProgress
            "POSITION" -> positionProgress
            "SCALE"    -> scaleProgress
            else       -> Triple(100, 100, 100)
        }

        withSync {
            val dynMax = modeMax()
            s1.max   = dynMax;  s2.max   = dynMax;  s3.max   = dynMax
            sUni.max = sclDynMax

            s1.progress   = p1;  s2.progress   = p2;  s3.progress   = p3
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

    private fun saveCurrentProgress() {
        when (currentMode) {
            "ROTATE"   -> rotateProgress    = Triple(s1.progress, s2.progress, s3.progress)
            "POSITION" -> positionProgress  = Triple(s1.progress, s2.progress, s3.progress)
            "SCALE"    -> scaleProgress     = Triple(s1.progress, s2.progress, s3.progress)
        }
    }

    // -------------------------------------------------------------------------
    // Apply to the 3-D node
    // -------------------------------------------------------------------------

    private fun applyToNode() {
        val node = targetNode ?: return
        val v1 = progressToValue(s1.progress)
        val v2 = progressToValue(s2.progress)
        val v3 = progressToValue(s3.progress)

        when (currentMode) {
            "POSITION" -> node.updatePosition(v1 / currentUnitFactor, v2 / currentUnitFactor, v3 / currentUnitFactor)
            "ROTATE"   -> node.updateRotation(v1, v2, v3)
            "SCALE"    -> node.updateScale(v1, v2, v3, progressToValue(sUni.progress))
        }
    }

    // -------------------------------------------------------------------------
    // Mapping functions
    // -------------------------------------------------------------------------

    private fun progressToValue(progress: Int): Float = when (currentMode) {
        "POSITION" -> (progress - posDynMid).toFloat()
        "ROTATE"   -> (progress - ROT_MID).toFloat()
        "SCALE"    -> (progress / SCL_MID.toFloat()).coerceAtLeast(MIN_SCALE_VALUE)
        else       -> progress.toFloat()
    }

    private fun valueToProgress(value: Float): Int = when (currentMode) {
        "POSITION" -> (value + posDynMid).toInt().coerceIn(POS_MIN, 2 * posDynMid)
        "ROTATE"   -> (value + ROT_MID).toInt().coerceIn(ROT_MIN, ROT_MAX)
        "SCALE"    -> (value * SCL_MID).toInt().coerceIn(SCL_MIN, sclDynMax)
        else       -> value.toInt()
    }

    // -------------------------------------------------------------------------
    // Dynamic range expansion
    // -------------------------------------------------------------------------

    private fun maybeExpandRange(value: Float) {
        when (currentMode) {
            "POSITION" -> {
                val needed = kotlin.math.abs(value).toInt()
                if (needed > posDynMid) {
                    posDynMid = needed.coerceAtMost(POS_MID_HARD)
                    log("Position range expanded → ±$posDynMid cm")
                }
            }
            "SCALE" -> {
                val neededMax = (value * SCL_MID).toInt()
                if (neededMax > sclDynMax) {
                    sclDynMax = neededMax.coerceAtMost(SCL_MAX_HARD)
                    log("Scale range expanded → max progress $sclDynMax (${sclDynMax / SCL_MID.toFloat()}×)")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private fun modeMax() = when (currentMode) {
        "POSITION" -> 2 * posDynMid
        "ROTATE"   -> ROT_MAX
        else       -> sclDynMax
    }

    private fun formatValue(value: Float) = String.format("%.1f", value)

    private var currentUnitFactor: Float = 100f
    fun updateUnit(unit: DistanceUnit) {
        val oldFactor = currentUnitFactor
        currentUnitFactor = when(unit) {
            DistanceUnit.METERS      -> 1f
            DistanceUnit.CENTIMETERS -> 100f
            DistanceUnit.MILLIMETERS -> 1000f
        }
        val multiplier = currentUnitFactor / oldFactor

        positionProgress = Triple(
            (((positionProgress.first - posDynMid) * multiplier) + posDynMid).toInt(),
            (((positionProgress.second - posDynMid) * multiplier) + posDynMid).toInt(),
            (((positionProgress.third - posDynMid) * multiplier) + posDynMid).toInt()
        )
        refreshSlidersForMode()
    }
}
