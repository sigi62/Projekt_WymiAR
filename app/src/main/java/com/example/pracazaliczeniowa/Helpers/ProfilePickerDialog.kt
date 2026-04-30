package com.example.pracazaliczeniowa.Helpers

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.example.pracazaliczeniowa.R

/**
 * A dialog that lets the user manage profiles for the currently selected model.
 *
 * ┌──────────────────────────────────────┐
 * │  Profiles – cat                      │
 * ├──────────────────────────────────────┤
 * │  ★ Default         [Overwrite][Load] │
 * │  ─────────────────────────────────── │
 * │  "Tiny"            [Overwrite][Load][✕]│
 * │  "Showroom"        [Overwrite][Load][✕]│
 * │  + Add named profile…                │
 * └──────────────────────────────────────┘
 *
 * Attach callbacks before showing:
 *   dialog.onLoadProfile  = { profile -> … }
 *   dialog.onProfileSaved = { … }          ← called after any save/overwrite
 *   dialog.onStatusUpdate = { message -> … }
 */
class ProfilePickerDialog : DialogFragment() {

    companion object {
        private const val ARG_MODEL_NAME = "modelName"

        fun newInstance(modelName: String) = ProfilePickerDialog().apply {
            arguments = Bundle().apply { putString(ARG_MODEL_NAME, modelName) }
        }
    }

    // Supplied by ARActivity before show()
    /** Called when the user taps Load on any slot. */
    var onLoadProfile:  ((ModelProfile) -> Unit)? = null
    /**
     * Called after any profile is saved or overwritten (default or named).
     * Use this to reset the overlay's sliders to neutral so the saved values
     * become the new baseline.
     */
    var onProfileSaved: (() -> Unit)? = null
    /** Called on save/delete to update the AR status bar. */
    var onStatusUpdate: ((String) -> Unit)? = null
    /** Must be set – provides the live scale/rotation to snapshot. */
    var getCurrentProfile: (() -> ModelProfile)? = null
    var onResetDefault: (() -> Unit)? = null

    private lateinit var modelName:     String
    private lateinit var profileManager: ProfileManager
    private lateinit var container:     LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelName      = requireArguments().getString(ARG_MODEL_NAME)!!
        profileManager = ProfileManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.dialog_profile_picker, parent, false)

        root.findViewById<TextView>(R.id.tvProfileDialogTitle).text =
            getString(R.string.dialog_profiles_title, modelName)

        container = root.findViewById(R.id.profileListContainer)
        buildList()

        root.findViewById<Button>(R.id.btnAddNamedProfile).setOnClickListener {
            promptAddNamed()
        }

        return root
    }

    // ─── Build / rebuild the slot list ───────────────────────────────────────

    private fun buildList() {
        container.removeAllViews()
        addDefaultRow()
        addDivider()
        profileManager.listNamedProfiles(modelName).forEach { name ->
            addNamedRow(name)
        }
    }

    // ── Default slot row ────────────────────────────────────────────────────

    private fun addDefaultRow() {
        val row = makeRowView()
        row.findViewById<TextView>(R.id.tvSlotName).text = getString(R.string.profile_label_default)

        val hasDefault = profileManager.loadDefault(modelName) != null
        row.findViewById<Button>(R.id.btnSlotLoad).apply {
            isEnabled = hasDefault
            setOnClickListener {
                val p = profileManager.loadDefault(modelName) ?: return@setOnClickListener
                dismiss()
                onLoadProfile?.invoke(p)
                onStatusUpdate?.invoke(getString(R.string.profile_loaded_default))
            }
        }
        row.findViewById<Button>(R.id.btnSlotOverwrite).apply {
            text = if (hasDefault) getString(R.string.overwrite) else getString(R.string.save)
            setOnClickListener {
                val current = getCurrentProfile?.invoke() ?: return@setOnClickListener
                profileManager.saveDefault(modelName, current)
                onStatusUpdate?.invoke(getString(R.string.profile_saved_default))
                onProfileSaved?.invoke()
                buildList()
            }
        }
        row.findViewById<ImageButton>(R.id.btnSlotDelete).apply {
            visibility = View.VISIBLE
            contentDescription = getString(R.string.profile_reset_btn_content_desc)
            setImageResource(R.drawable.ic_revert)
            setOnClickListener { confirmResetToOriginal() }
        }

        container.addView(row)
    }

    // ── Named slot row ───────────────────────────────────────────────────────

    private fun addNamedRow(slotName: String) {
        val row = makeRowView()
        row.findViewById<TextView>(R.id.tvSlotName).text = slotName

        row.findViewById<Button>(R.id.btnSlotLoad).setOnClickListener {
            val p = profileManager.loadNamed(modelName, slotName) ?: return@setOnClickListener
            dismiss()
            onLoadProfile?.invoke(p)
            onStatusUpdate?.invoke(getString(R.string.profile_loaded_named, slotName))
        }
        row.findViewById<Button>(R.id.btnSlotOverwrite).apply {
            text = getString(R.string.overwrite)
            setOnClickListener {
                val current = getCurrentProfile?.invoke() ?: return@setOnClickListener
                profileManager.saveNamed(modelName, slotName, current)
                onStatusUpdate?.invoke(getString(R.string.profile_updated_named, slotName))
                onProfileSaved?.invoke()
                buildList()
            }
        }
        row.findViewById<ImageButton>(R.id.btnSlotDelete).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                confirmDelete(slotName)
            }
        }

        container.addView(row)
    }

    // ── Prompt: add new named slot ───────────────────────────────────────────

    private fun promptAddNamed() {
        val namedCount = profileManager.listNamedProfiles(modelName).size
        if (namedCount >= ProfileManager.MAX_NAMED) {
            Toast.makeText(
                requireContext(),
                getString(R.string.error_too_many_profiles, ProfileManager.MAX_NAMED),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val input = EditText(requireContext()).apply {
            hint       = getString(R.string.dialog_add_profile_hint)
            maxLines   = 1
            inputType  = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_add_profile_title))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.profile_error_name_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val current = getCurrentProfile?.invoke()
                if (current == null) {
                    Toast.makeText(requireContext(), getString(R.string.profile_error_no_model), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                when (profileManager.saveNamed(modelName, name, current)) {
                    ProfileSaveResult.Success -> {
                        onStatusUpdate?.invoke(getString(R.string.profile_saved_named, name))
                        onProfileSaved?.invoke()
                        buildList()
                    }
                    ProfileSaveResult.TooManyProfiles -> {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.error_too_many_profiles, ProfileManager.MAX_NAMED),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is ProfileSaveResult.Error -> {
                        Toast.makeText(requireContext(), getString(R.string.profile_error_save_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Confirm delete ───────────────────────────────────────────────────────

    private fun confirmDelete(slotName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profile_delete_slot_title, slotName))
            .setMessage(getString(R.string.profile_delete_slot_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                profileManager.deleteNamed(modelName, slotName)
                onStatusUpdate?.invoke(getString(R.string.profile_deleted_named, slotName))
                buildList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    private fun confirmResetToOriginal() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profile_reset_default_title))
            .setMessage(getString(R.string.profile_reset_default_message))
            .setPositiveButton(getString(R.string.reset)) { _, _ ->
                val original = ModelProfile(
                    scaleX = 1f, scaleY = 1f, scaleZ = 1f,
                    rotationX = 0f, rotationY = 0f, rotationZ = 0f
                )
                profileManager.saveDefault(modelName, original)
                dismiss()
                onLoadProfile?.invoke(original)
                onProfileSaved?.invoke()
                onResetDefault?.invoke()
                onStatusUpdate?.invoke(getString(R.string.status_reset_done))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun makeRowView(): View =
        LayoutInflater.from(requireContext())
            .inflate(R.layout.item_profile_slot, container, false)

    private fun addDivider() {
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.setMargins(0, 8, 0, 8) }
            setBackgroundColor(0x33FFFFFF)
        }
        container.addView(divider)
    }

    override fun onStart() {
        super.onStart()
        // Make the dialog wider than the default narrow AlertDialog style
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}