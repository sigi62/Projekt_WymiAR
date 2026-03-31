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
import com.example.pracazaliczeniowa.Nodes.SelectedModelNode
import com.example.pracazaliczeniowa.R

fun log(msg: String) {
    Log.d("AR_CONTROL_DEBUG", msg)
}

class ModelControlOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    private companion object {
        // Position: progress 0..200, mid=100  →  value = progress - 100  (cm)
        const val POS_MIN = 0;   const val POS_MAX = 200;  const val POS_MID = 100

        // Rotation: progress 0..360, mid=180  →  value = progress - 180  (degrees)
        const val ROT_MIN = 0;   const val ROT_MAX = 360;  const val ROT_MID = 180

        // Scale:    progress 1..500, mid=100  →  value = progress / 100f  (multiplier)
        const val SCL_MIN = 1;   const val SCL_MAX = 500;  const val SCL_MID = 100

        const val MIN_SCALE_VALUE = 0.01f

        /** Delay after the last keystroke before committing an EditText value (ms). */
        const val EDIT_COMMIT_DELAY_MS = 2000L
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var targetNode: SelectedModelNode? = null
    private var currentMode = "ROTATE"

    /** Persisted progress values per mode so switching tabs restores sliders. */
    private var positionProgress    = Triple(POS_MID, POS_MID, POS_MID)
    private var scaleProgress       = Triple(SCL_MID, SCL_MID, SCL_MID)
    private var rotateProgress      = Triple(ROT_MID, ROT_MID, ROT_MID)
    private var universalScaleProgress = SCL_MID

    // -------------------------------------------------------------------------
    // Sync guard — prevents seekbar ↔ editText feedback loops
    // -------------------------------------------------------------------------

    /**
     * When true, programmatic changes to seekbars / editTexts are ignored by
     * their respective listeners.  Always set via [withSync] to guarantee it
     * is reset even if an exception is thrown.
     */
    private var isSyncing = false

    private inline fun withSync(block: () -> Unit) {
        isSyncing = true
        try { block() } finally { isSyncing = false }
    }

    // -------------------------------------------------------------------------
    // Debounce infrastructure for EditText commit
    // -------------------------------------------------------------------------

    private val commitHandler = Handler(Looper.getMainLooper())

    /** One pending runnable per EditText; cancelled on each new keystroke. */
    private val pendingCommit = HashMap<EditText, Runnable>()

    /**
     * Schedule [action] to run [EDIT_COMMIT_DELAY_MS] ms after the last
     * keystroke into [editText].  Any previously pending runnable for that
     * EditText is cancelled first.
     */
    private fun scheduleCommit(editText: EditText, action: () -> Unit) {
        pendingCommit[editText]?.let { commitHandler.removeCallbacks(it) }
        val runnable = Runnable { action() }
        pendingCommit[editText] = runnable
        commitHandler.postDelayed(runnable, EDIT_COMMIT_DELAY_MS)
    }

    /** Cancel a pending commit for [editText] (e.g. when focus is lost and we commit immediately). */
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

    /** Called by a pinch gesture; updates the universal-scale progress and applies it. */
    fun updateScaleFromGesture(factor: Float) {
        val sensitivity = 10f
        val delta = ((factor - 1.0f) * sensitivity).toInt()
        val newProgress = (universalScaleProgress + delta).coerceIn(SCL_MIN, SCL_MAX)
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

    /**
     * Attaches a [android.text.TextWatcher] for debounced commits and an
     * [OnFocusChangeListener] / IME action listener for immediate commit on blur
     * or "Done" key press.  [onCommit] is called when the value should be
     * applied; it runs outside [isSyncing] so it can safely update seekbars.
     */
    private fun attachEditTextListeners(editText: EditText, onCommit: () -> Unit) {
        // Debounce: schedule a commit 2 s after each keystroke
        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isSyncing) return  // programmatic change — ignore
                scheduleCommit(editText, onCommit)
            }
        })

        // Immediate commit when the field loses focus
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                cancelCommit(editText)  // cancel the pending debounce
                onCommit()              // commit right now
                dismissKeyboard(editText)
            }
        }

        // Immediate commit on IME "Done" action
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

    /**
     * Reads i1/i2/i3, clamps values to the current mode's legal range,
     * updates the seekbars (and the canonical display text), then applies.
     */
    private fun commitEditTexts() {
        val v1 = i1.text.toString().toFloatOrNull() ?: progressToValue(s1.progress)
        val v2 = i2.text.toString().toFloatOrNull() ?: progressToValue(s2.progress)
        val v3 = i3.text.toString().toFloatOrNull() ?: progressToValue(s3.progress)

        val p1 = valueToProgress(v1)
        val p2 = valueToProgress(v2)
        val p3 = valueToProgress(v3)

        withSync {
            s1.progress = p1;  s2.progress = p2;  s3.progress = p3
            // Clamp-correct the text so the user sees the capped value
            i1.setText(formatValue(progressToValue(p1)))
            i2.setText(formatValue(progressToValue(p2)))
            i3.setText(formatValue(progressToValue(p3)))
            // Move cursor to end for a nicer feel
            i1.setSelection(i1.text.length)
            i2.setSelection(i2.text.length)
            i3.setSelection(i3.text.length)
            saveCurrentProgress()
        }

        log("Committed [$currentMode] values: $v1, $v2, $v3  →  progress: $p1, $p2, $p3")
        applyToNode()
    }

    /** Same for the universal-scale field. */
    private fun commitUniScaleEditText() {
        if (currentMode != "SCALE") return

        val v = iUni.text.toString().toFloatOrNull() ?: progressToValue(sUni.progress)
        val p = valueToProgress(v).coerceIn(SCL_MIN, SCL_MAX)
        universalScaleProgress = p

        withSync {
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

    /** Restores seekbars and EditTexts when switching modes. */
    private fun refreshSlidersForMode() {
        val (p1, p2, p3) = when (currentMode) {
            "ROTATE"   -> rotateProgress
            "POSITION" -> positionProgress
            "SCALE"    -> scaleProgress
            else       -> Triple(100, 100, 100)
        }

        withSync {
            s1.max  = modeMax();  s2.max  = modeMax();  s3.max  = modeMax()
            sUni.max = SCL_MAX

            s1.progress  = p1;  s2.progress  = p2;  s3.progress  = p3
            sUni.progress = universalScaleProgress

            i1.setText(formatValue(progressToValue(p1)))
            i2.setText(formatValue(progressToValue(p2)))
            i3.setText(formatValue(progressToValue(p3)))
            if (currentMode == "SCALE") {
                iUni.setText(formatValue(progressToValue(universalScaleProgress)))
            }
        }
    }

    /** Syncs EditTexts to the current seekbar positions (called from the seek listener). */
    private fun refreshEditTexts() {
        i1.setText(formatValue(progressToValue(s1.progress)))
        i2.setText(formatValue(progressToValue(s2.progress)))
        i3.setText(formatValue(progressToValue(s3.progress)))
        if (currentMode == "SCALE") {
            iUni.setText(formatValue(progressToValue(sUni.progress)))
        }
    }

    /** Writes the current seekbar positions back into the per-mode state stores. */
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

        val cmToMetres = 100f

        when (currentMode) {
            "POSITION" -> node.updatePosition(v1 / cmToMetres, v2 / cmToMetres, v3 / cmToMetres)
            "ROTATE"   -> node.updateRotation(v1, v2, v3)
            "SCALE"    -> node.updateScale(v1, v2, v3, progressToValue(sUni.progress))
        }
    }

    // -------------------------------------------------------------------------
    // Mapping functions
    // -------------------------------------------------------------------------

    /**
     * Converts a seekbar integer progress value to the human-readable float
     * displayed in the EditTexts and sent to the node.
     *
     * | Mode     | Progress range | Value range       | Example             |
     * |----------|----------------|-------------------|---------------------|
     * | POSITION | 0 – 200        | -100 … +100 cm    | 115 → 15.0          |
     * | ROTATE   | 0 – 360        | -180 … +180 °     | 180 → 0.0           |
     * | SCALE    | 1 – 500        | 0.01 … 5.00 ×     | 206 → 2.06          |
     */
    private fun progressToValue(progress: Int): Float = when (currentMode) {
        "POSITION" -> (progress - POS_MID).toFloat()                           // 115 → 15.0
        "ROTATE"   -> (progress - ROT_MID).toFloat()                           // 180 → 0.0
        "SCALE"    -> (progress / SCL_MID.toFloat()).coerceAtLeast(MIN_SCALE_VALUE) // 206 → 2.06
        else       -> progress.toFloat()
    }

    /**
     * Inverse of [progressToValue] — clamps to the mode's legal range.
     *
     * | Mode     | Value        | Progress          |
     * |----------|--------------|-------------------|
     * | POSITION | 15.0         | 115               |
     * | ROTATE   | 0.0          | 180               |
     * | SCALE    | 2.06         | 206               |
     */
    private fun valueToProgress(value: Float): Int = when (currentMode) {
        "POSITION" -> (value + POS_MID).toInt().coerceIn(POS_MIN, POS_MAX)
        "ROTATE"   -> (value + ROT_MID).toInt().coerceIn(ROT_MIN, ROT_MAX)
        "SCALE"    -> (value * SCL_MID).toInt().coerceIn(SCL_MIN, SCL_MAX)
        else       -> value.toInt()
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private fun modeMax() = when (currentMode) {
        "POSITION" -> POS_MAX
        "ROTATE"   -> ROT_MAX
        else       -> SCL_MAX
    }

    /** One decimal place is enough for all three modes. */
    private fun formatValue(value: Float) = String.format("%.1f", value)
}
