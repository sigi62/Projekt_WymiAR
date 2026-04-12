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
    /** Called on save/delete to update the AR status bar. */
    var onStatusUpdate: ((String) -> Unit)? = null
    /** Must be set – provides the live scale/rotation to snapshot. */
    var getCurrentProfile: (() -> ModelProfile)? = null

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
            "Profiles – $modelName"

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
        row.findViewById<TextView>(R.id.tvSlotName).text = "★ Default"

        val hasDefault = profileManager.loadDefault(modelName) != null
        row.findViewById<Button>(R.id.btnSlotLoad).apply {
            isEnabled = hasDefault
            setOnClickListener {
                val p = profileManager.loadDefault(modelName) ?: return@setOnClickListener
                onLoadProfile?.invoke(p)
                onStatusUpdate?.invoke("Loaded default profile")
                dismiss()
            }
        }
        row.findViewById<Button>(R.id.btnSlotOverwrite).apply {
            text = if (hasDefault) "Overwrite" else "Save"
            setOnClickListener {
                val current = getCurrentProfile?.invoke() ?: return@setOnClickListener
                profileManager.saveDefault(modelName, current)
                onStatusUpdate?.invoke("Default profile saved")
                buildList()   // refresh so button label updates
            }
        }
        // No delete button for the default slot
        row.findViewById<ImageButton>(R.id.btnSlotDelete).visibility = View.GONE

        container.addView(row)
    }

    // ── Named slot row ───────────────────────────────────────────────────────

    private fun addNamedRow(slotName: String) {
        val row = makeRowView()
        row.findViewById<TextView>(R.id.tvSlotName).text = slotName

        row.findViewById<Button>(R.id.btnSlotLoad).setOnClickListener {
            val p = profileManager.loadNamed(modelName, slotName) ?: return@setOnClickListener
            onLoadProfile?.invoke(p)
            onStatusUpdate?.invoke("Loaded profile \"$slotName\"")
            dismiss()
        }
        row.findViewById<Button>(R.id.btnSlotOverwrite).apply {
            text = "Overwrite"
            setOnClickListener {
                val current = getCurrentProfile?.invoke() ?: return@setOnClickListener
                profileManager.saveNamed(modelName, slotName, current)
                onStatusUpdate?.invoke("Profile \"$slotName\" updated")
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
                "Maximum of ${ProfileManager.MAX_NAMED} named profiles reached.\nDelete one to add another.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val input = EditText(requireContext()).apply {
            hint       = "Profile name"
            maxLines   = 1
            inputType  = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        AlertDialog.Builder(requireContext())
            .setTitle("New profile name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val current = getCurrentProfile?.invoke()
                if (current == null) {
                    Toast.makeText(requireContext(), "No model selected", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                when (profileManager.saveNamed(modelName, name, current)) {
                    ProfileSaveResult.Success -> {
                        onStatusUpdate?.invoke("Saved profile \"$name\"")
                        buildList()
                    }
                    ProfileSaveResult.TooManyProfiles -> {
                        Toast.makeText(
                            requireContext(),
                            "Max ${ProfileManager.MAX_NAMED} profiles reached",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is ProfileSaveResult.Error -> {
                        Toast.makeText(requireContext(), "Save failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Confirm delete ───────────────────────────────────────────────────────

    private fun confirmDelete(slotName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete \"$slotName\"?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                profileManager.deleteNamed(modelName, slotName)
                onStatusUpdate?.invoke("Deleted profile \"$slotName\"")
                buildList()
            }
            .setNegativeButton("Cancel", null)
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