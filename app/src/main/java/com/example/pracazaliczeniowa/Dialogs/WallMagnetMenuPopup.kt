package com.example.pracazaliczeniowa.Helpers

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.pracazaliczeniowa.Objects.PlaneMode
import com.example.pracazaliczeniowa.R

/**
 * WallMagnetMenuPopup
 * ───────────────────
 * A lightweight PopupWindow that fans out above [anchorView] and lets the
 * user choose between the four [PlaneMode] options.
 *
 * Usage:
 *   val popup = WallMagnetMenuPopup(context)
 *   popup.onModeSelected = { mode -> … }
 *   popup.show(anchorView, currentMode)
 */
class WallMagnetMenuPopup(private val context: Context) {

    /** Called when the user taps one of the four mode buttons. */
    var onModeSelected: ((PlaneMode) -> Unit)? = null

    private var popup: PopupWindow? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun show(anchorView: View, currentMode: PlaneMode) {
        dismiss()

        val content = buildContentView(currentMode) { mode ->
            onModeSelected?.invoke(mode)
            dismiss()
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true  // focusable → dismiss on outside touch
        ).apply {
            isOutsideTouchable = true
            elevation = 8f
            animationStyle = 0  // we do our own animation
        }

        // Anchor the popup to the left of the button, vertically centred on it.
        val xOffset = -(content.measuredWidth + dp(8))   // shift left of the button
        val yOffset = -(content.measuredHeight / 2 + anchorView.height / 2)  // centre vertically

        popup?.showAsDropDown(anchorView, xOffset, yOffset)

        animateIn(content)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    fun isShowing() = popup?.isShowing == true

    // ── View construction ─────────────────────────────────────────────────────

    /**
     * Builds a vertical stack of four option rows. Each row has:
     *   • an [ImageButton] showing the ic_stack icon in the appropriate rotation/alpha
     *   • a short [TextView] label
     * The row matching [currentMode] is highlighted.
     */
    private fun buildContentView(
        currentMode: PlaneMode,
        onPick: (PlaneMode) -> Unit
    ): LinearLayout {

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(R.drawable.rounded_button) // reuse your existing background
        }

        val entries = listOf(
            Entry(PlaneMode.HORIZONTAL, "Floor / Ceiling",  0f,   false),
            Entry(PlaneMode.VERTICAL,   "Wall",            -90f,  false),
            Entry(PlaneMode.BOTH,        "All surfaces",   -45f,  false),
            Entry(PlaneMode.OFF,         "Hide planes",     0f,   true),   // crossed-out style
        )

        entries.forEach { entry ->
            val row = buildRow(entry, currentMode == entry.mode, onPick)
            container.addView(row)
        }

        return container
    }

    private data class Entry(
        val mode: PlaneMode,
        val label: String,
        val iconRotation: Float,
        val isOff: Boolean
    )

    private fun buildRow(
        entry: Entry,
        isSelected: Boolean,
        onPick: (PlaneMode) -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val vPad = dp(6)
            val hPad = dp(10)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            setBackgroundResource(
                if (isSelected) R.drawable.rounded_button
                else android.R.color.transparent
            )
            setOnClickListener { onPick(entry.mode) }
        }

        val icon = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setImageResource(R.drawable.ic_stack)
            background = null
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))

            rotation = entry.iconRotation

            // OFF mode: dim the icon to signal disabled state
            alpha = if (entry.isOff) 0.35f else if (isSelected) 1.0f else 0.65f

            // Non-interactive — the row handles clicks
            isClickable = false
            isFocusable = false
        }

        val label = TextView(context).apply {
            text = entry.label
            textSize = 13f
            setTextColor(
                if (isSelected)
                    ContextCompat.getColor(context, android.R.color.white)
                else
                    ContextCompat.getColor(context, android.R.color.white).let {
                        // 70 % opacity for non-selected labels
                        android.graphics.Color.argb(
                            (android.graphics.Color.alpha(it) * 0.70f).toInt(),
                            android.graphics.Color.red(it),
                            android.graphics.Color.green(it),
                            android.graphics.Color.blue(it)
                        )
                    }
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            layoutParams = lp
        }

        row.addView(icon)
        row.addView(label)
        return row
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    /**
     * Slides the popup up from the anchor and fades it in.
     * Each row staggers slightly for a fan-out feel.
     */
    private fun animateIn(container: LinearLayout) {
        container.alpha = 0f
        container.translationY = dp(20).toFloat()

        val fade  = ObjectAnimator.ofFloat(container, "alpha",        0f, 1f)
        val slide = ObjectAnimator.ofFloat(container, "translationY", dp(20).toFloat(), 0f)

        AnimatorSet().apply {
            playTogether(fade, slide)
            duration = 180
            start()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}