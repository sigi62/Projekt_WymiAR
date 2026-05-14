package com.example.pracazaliczeniowa.Helpers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.pracazaliczeniowa.R

/**
 * Pick-only profile dialog for the "Export with profile" flow.
 *
 * Shows all saved profiles (default first, then named) as a list of rows.
 * Tapping a row fires [onProfileSelected] and dismisses the dialog.
 *
 * Usage:
 *   ExportProfilePickerDialog.newInstance(modelName, item.name).apply {
 *       onProfileSelected = { label, profile -> … }
 *   }.show(supportFragmentManager, "export_profile")
 */
class ExportProfilePickerDialog : DialogFragment() {

    companion object {
        private const val ARG_MODEL_NAME   = "modelName"
        private const val ARG_DISPLAY_NAME = "displayName"

        fun newInstance(modelName: String, displayName: String) =
            ExportProfilePickerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODEL_NAME,   modelName)
                    putString(ARG_DISPLAY_NAME, displayName)
                }
            }
    }

    /** Called with the chosen profile label and profile when the user picks one. */
    var onProfileSelected: ((label: String, profile: ModelProfile) -> Unit)? = null

    private lateinit var modelName:      String
    private lateinit var displayName:    String
    private lateinit var profileManager: ProfileManager
    private lateinit var container:      LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelName      = requireArguments().getString(ARG_MODEL_NAME)!!
        displayName    = requireArguments().getString(ARG_DISPLAY_NAME)!!
        profileManager = ProfileManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.dialog_export_profile_picker, parent, false)

        root.findViewById<TextView>(R.id.tvExportDialogTitle).text =
            getString(R.string.dialog_export_pick_profile, displayName)

        container = root.findViewById(R.id.exportProfileListContainer)
        buildList()

        root.findViewById<Button>(R.id.btnExportCancel).setOnClickListener { dismiss() }

        return root
    }

    private fun buildList() {
        container.removeAllViews()
        val profiles = profileManager.listExportableProfiles(modelName)

        profiles.forEach { (label, profile) ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_profile_slot, container, false)

            row.findViewById<TextView>(R.id.tvSlotName).text = label

            // Only the Export button is relevant here — hide the others
            row.findViewById<Button>(R.id.btnSlotOverwrite).visibility = View.GONE
            row.findViewById<android.widget.ImageButton>(R.id.btnSlotDelete).visibility = View.GONE

            row.findViewById<Button>(R.id.btnSlotLoad).apply {
                text = getString(R.string.btn_export)
                setOnClickListener {
                    dismiss()
                    onProfileSelected?.invoke(label, profile)
                }
            }

            container.addView(row)

            // Divider between rows
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(0, 8, 0, 8) }
                setBackgroundColor(0x33FFFFFF)
            }
            container.addView(divider)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}