package com.example.WymiAR.Dialogs

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.example.WymiAR.Managers.ModelProfile
import com.example.WymiAR.Managers.ProfileManager
import com.example.WymiAR.Managers.ProfileSaveResult
import com.example.WymiAR.R


class ProfilePickerDialog : DialogFragment() {

    companion object {
        private const val ARG_MODEL_ID = "modelID"
        private const val ARG_MODEL_NAME = "modelName"
        const val SLOT_DEFAULT = "\$default"
        fun newInstance(modelId: String,modelName: String) = ProfilePickerDialog().apply {
            arguments = Bundle().apply { putString(ARG_MODEL_ID, modelId)
                putString(ARG_MODEL_NAME, modelName) }
        }
    }

    var onLoadProfile: ((ModelProfile) -> Unit)? = null
    var lastLoadedProfileName: String? = null
    var activeProfileName: String? = null
    var onDefaultProfileSaved: ((ModelProfile) -> Unit)? = null
    var onNamedProfileSaved: ((String, ModelProfile) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var getCurrentProfile: (() -> ModelProfile)? = null
    var onResetDefault: (() -> Unit)? = null

    private lateinit var modelId: String
    private lateinit var modelName: String
    private lateinit var profileManager: ProfileManager
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelId = requireArguments().getString(ARG_MODEL_ID)!!
        modelName = requireArguments().getString(ARG_MODEL_NAME)!!
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

    private fun buildList() {
        container.removeAllViews()
        addDefaultRow()
        addDivider()
        profileManager.listNamedProfiles(modelId).forEach { name ->
            addNamedRow(name)
        }
    }

    private fun addDefaultRow() {
        val row = makeRowView()
        row.findViewById<TextView>(R.id.tvSlotName).text =
            buildSlotLabel(getString(R.string.profile_label_default), SLOT_DEFAULT)

        val hasDefault = profileManager.loadDefault(modelId) != null

        row.findViewById<Button>(R.id.btnSlotLoad).apply {
            isEnabled = hasDefault
            setOnClickListener {
                val p = profileManager.loadDefault(modelId) ?: return@setOnClickListener
                lastLoadedProfileName = SLOT_DEFAULT
                dismiss()
                onLoadProfile?.invoke(p)
                onStatusUpdate?.invoke(getString(R.string.profile_loaded_default))
            }
        }

        row.findViewById<Button>(R.id.btnSlotOverwrite).apply {
            text = if (hasDefault) getString(R.string.overwrite) else getString(R.string.save)
            setOnClickListener {
                val current = getCurrentProfile?.invoke() ?: return@setOnClickListener
                profileManager.saveDefault(modelId, current)
                onStatusUpdate?.invoke(getString(R.string.profile_saved_default))
                onDefaultProfileSaved?.invoke(current)
                dismiss()
            }
        }

        row.findViewById<ImageButton>(R.id.btnSlotDelete).apply {
            visibility = View.VISIBLE
            contentDescription = getString(R.string.profile_reset_btn_content_desc)
            setImageResource(R.drawable.ic_revert)
            imageTintList = ContextCompat.getColorStateList(context, R.color.icon_tint)
            setOnClickListener { confirmResetToOriginal() }
        }

        container.addView(row)
    }

    private fun addNamedRow(slotName: String) {
        val row = makeRowView()
        row.findViewById<TextView>(R.id.tvSlotName).text = buildSlotLabel(slotName, slotName)

        row.findViewById<Button>(R.id.btnSlotLoad).setOnClickListener {
            val p = profileManager.loadNamed(modelId, slotName) ?: return@setOnClickListener
            lastLoadedProfileName = slotName
            dismiss()
            onLoadProfile?.invoke(p)
            onStatusUpdate?.invoke(getString(R.string.profile_loaded_named, slotName))
        }

        row.findViewById<Button>(R.id.btnSlotOverwrite).apply {
            text = getString(R.string.overwrite)
            setOnClickListener {
                val current = getCurrentProfile?.invoke() ?: return@setOnClickListener
                profileManager.saveNamed(modelId, slotName, current)
                onStatusUpdate?.invoke(getString(R.string.profile_updated_named, slotName))
                onNamedProfileSaved?.invoke(slotName, current)
                dismiss()
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

    private fun promptAddNamed() {
        val namedCount = profileManager.listNamedProfiles(modelId).size
        if (namedCount >= ProfileManager.MAX_NAMED) {
            Toast.makeText(
                requireContext(),
                getString(R.string.error_too_many_profiles, ProfileManager.Companion.MAX_NAMED),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_input, null)
        val input = view.findViewById<EditText>(R.id.etRenameInput).apply {
            hint = getString(R.string.dialog_add_profile_hint)
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        view.findViewById<TextView>(R.id.tvDialogTitle).text = getString(R.string.dialog_add_profile_title)
        view.findViewById<Button>(R.id.btnConfirm).text = getString(R.string.save)

        val dialog = android.app.Dialog(requireContext()).apply {
            setContentView(view)
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.profile_error_name_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = getCurrentProfile?.invoke()
            if (current == null) {
                Toast.makeText(requireContext(), getString(R.string.profile_error_no_model), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            when (profileManager.saveNamed(modelId, name, current)) {
                ProfileSaveResult.Success -> {
                    onStatusUpdate?.invoke(getString(R.string.profile_saved_named, name))
                    onNamedProfileSaved?.invoke(name, current)
                    dialog.dismiss()
                    buildList()
                }
                ProfileSaveResult.TooManyProfiles -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_too_many_profiles, ProfileManager.Companion.MAX_NAMED),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is ProfileSaveResult.Error -> {
                    Toast.makeText(requireContext(), getString(R.string.profile_error_save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT
        )
        input.post {
            input.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun confirmDelete(slotName: String) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = getString(R.string.profile_delete_slot_title, slotName)
        view.findViewById<TextView>(R.id.tvDialogMessage).text = getString(R.string.profile_delete_slot_message)
        view.findViewById<Button>(R.id.btnConfirm).text = getString(R.string.delete)

        val dialog = android.app.Dialog(requireContext()).apply { setContentView(view) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            profileManager.deleteNamed(modelId, slotName)
            onStatusUpdate?.invoke(getString(R.string.profile_deleted_named, slotName))
            dialog.dismiss()
            buildList()
        }
        dialog.show()
        dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
        ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun confirmResetToOriginal() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.tvDialogTitle).text = getString(R.string.profile_reset_default_title)
        view.findViewById<TextView>(R.id.tvDialogMessage).text = getString(R.string.profile_reset_default_message)
        view.findViewById<Button>(R.id.btnConfirm).text = getString(R.string.reset)

        val dialog = android.app.Dialog(requireContext()).apply { setContentView(view) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val original = ModelProfile(
                scaleX = 1f, scaleY = 1f, scaleZ = 1f,
                rotationX = 0f, rotationY = 0f, rotationZ = 0f
            )
            profileManager.saveDefault(modelId, original)
            lastLoadedProfileName = SLOT_DEFAULT
            dialog.dismiss()
            dismiss()
            onLoadProfile?.invoke(original)
            onDefaultProfileSaved?.invoke(original)
            onResetDefault?.invoke()
            onStatusUpdate?.invoke(getString(R.string.status_reset_done))
        }
        dialog.show()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildSlotLabel(displayName: String, slotKey: String): String =
        if (slotKey == activeProfileName) "▶ $displayName" else displayName

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
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}