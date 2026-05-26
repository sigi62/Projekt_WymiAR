package com.example.WymiAR.Activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.WymiAR.Dialogs.ExportProfilePickerDialog
import com.example.WymiAR.Helpers.GlbTransformExporter
import com.example.WymiAR.Managers.LibraryFilterManager
import com.example.WymiAR.Managers.ModelImportManager
import com.example.WymiAR.Managers.ModelLibraryManager
import com.example.WymiAR.Managers.ModelProfile
import com.example.WymiAR.Managers.ProfileManager
import com.example.WymiAR.Objects.ModelItem
import com.example.WymiAR.R
import com.google.android.material.chip.Chip
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH     = "extra_model_path"
        const val EXTRA_MODEL_ID    = "extra_model_id"
        const val EXTRA_MODEL_IS_ASSET = "extra_model_is_asset"
        const val EXTRA_SOURCE_FORMAT  = "extra_source_format"
    }


    val modelMimeTypes = arrayOf(
        "model/obj",
        "model/stl",
        "application/sla",
        "application/vnd.ms-pki.stl",
        "model/gltf-binary",
        "model/gltf+json",
        "model/fbx",
        "model/vnd.collada+xml",
        "image/x-3ds",
        "application/octet-stream"
    )

    // ── Result launchers ──────────────────────────────────────────────────────

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) recreate()
    }

    private val arLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { adapter.notifyDataSetChanged() }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshAllModels()
        adapter.notifyDataSetChanged()
    }
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult

        val fileName  = getFileName(uri) ?: "unknown"
        val extension = fileName.substringAfterLast(".", "").lowercase()
        val allowed   = setOf("obj", "stl", "glb", "gltf", "fbx", "dae", "3ds", "ply")

        if (extension in allowed) {
            processImport(uri)
        } else {
            Toast.makeText(
                this,
                getString(R.string.toast_unsupported_format, extension),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var selectedModelKey: String? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ModelLibraryManager

    private lateinit var filterManager: LibraryFilterManager
    private val allModels = mutableListOf<ModelItem>()

    private val splitInstallListener = SplitInstallStateUpdatedListener { state ->
        when (state.status()) {
            SplitInstallSessionStatus.INSTALLED -> {
                Toast.makeText(this, getString(R.string.toast_converter_ready), Toast.LENGTH_SHORT)
                    .show()
                importLauncher.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, modelMimeTypes)
                    }
                )
            }

            SplitInstallSessionStatus.FAILED -> {
                Toast.makeText(
                    this,
                    getString(R.string.toast_converter_failed, state.errorCode().toString()),
                    Toast.LENGTH_LONG
                ).show()
            }

            SplitInstallSessionStatus.DOWNLOADING -> {
                Toast.makeText(
                    this,
                    getString(R.string.toast_converter_downloading),
                    Toast.LENGTH_SHORT
                ).show()
            }

            else -> {
            }
        }
    }

    private val bundledModels: List<ModelItem> by lazy {
        (this.assets.list("models") ?: emptyArray())
            .filter { it.endsWith(".glb", ignoreCase = true) }
            .map { ModelImportManager.bundledItem(this, "models/$it") }
    }


    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private lateinit var profileManager: ProfileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        val splitInstallManager = SplitInstallManagerFactory.create(this)
        splitInstallManager.registerListener(splitInstallListener)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }


        findViewById<ImageButton>(R.id.btnImportModel).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, modelMimeTypes)
            }
            importLauncher.launch(intent)
        }

        allModels.clear()

        allModels.addAll(bundledModels)
        allModels.addAll(ModelImportManager.loadImported(this).also { imports ->
            imports.forEach { ModelImportManager.verifyImport(this, it) }
        })

        profileManager = ProfileManager(this)
        val profileSaved = allModels
            .filter { profileManager.hasAnyProfile(it.modelId) }
            .map    { it.modelId }
            .toSet()

        filterManager = LibraryFilterManager(profileSaved)
        wireFilterChips()

        recyclerView = findViewById(R.id.rvModelLibrary)
        recyclerView.layoutManager = GridLayoutManager(this, 2)


        adapter = ModelLibraryManager(
            items = allModels.toList(),
            savedProfiles = profileSaved,
            selectedModelId = selectedModelKey,
            onItemClick = { selectedModel ->
                selectedModelKey = selectedModel.modelId
                adapter.updateSelection(selectedModelKey)
                arLauncher.launch(
                    Intent(this, ARActivity::class.java).apply {
                        putExtra(EXTRA_MODEL_PATH, selectedModel.modelPath)
                        putExtra(EXTRA_MODEL_ID, selectedModel.modelId)
                        putExtra(EXTRA_MODEL_IS_ASSET, selectedModel.isAsset)
                        putExtra(EXTRA_SOURCE_FORMAT, selectedModel.sourceFormat)
                    }
                )
            },
            onPreviewClick = { selectedModel ->
                previewLauncher.launch(
                    Intent(this, ModelPreviewActivity::class.java).apply {
                        putExtra(
                            ModelPreviewActivity.Companion.EXTRA_MODEL_PATH,
                            selectedModel.modelPath
                        )
                        putExtra(
                            ModelPreviewActivity.Companion.EXTRA_MODEL_IS_ASSET,
                            selectedModel.isAsset
                        )
                        putExtra(
                            ModelPreviewActivity.Companion.EXTRA_MODEL_ID,
                            selectedModel.modelId
                        )
                        putExtra(
                            ModelPreviewActivity.Companion.EXTRA_SOURCE_FORMAT,
                            selectedModel.sourceFormat
                        )
                    }
                )
            },
            onDeleteImported = { deleteImportedModel(it) },
            onExportWithProfile = { startExportFlow(it) },
            onRenameImported = { showRenameDialog(it) },
            loadDefaultProfile = { key -> profileManager.loadDefault(key) }
        )

        recyclerView.adapter = adapter
        updateCountLabel()
    }


    // ── Import flow ───────────────────────────────────────────────────────────

    private fun processImport(uri: Uri) {
        val fileName = ModelImportManager.resolveFileName(this, uri) ?: ""
        val ext      = fileName.substringAfterLast('.', "").lowercase()

        val needsConvert = ext != "glb"

        if (!needsConvert) {
            runImportAsync(uri)
            return
        }

        val manager = SplitInstallManagerFactory.create(this)
        if (manager.installedModules.contains("converter")) {
            runImportAsync(uri)
        } else {
            Toast.makeText(this, getString(R.string.toast_converter_downloading), Toast.LENGTH_SHORT).show()
            val request = SplitInstallRequest.newBuilder()
                .addModule("converter")
                .build()
            manager.startInstall(request)
                .addOnFailureListener { e ->
                    Toast.makeText(this, getString(R.string.toast_converter_download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun runImportAsync(uri: Uri) {
        Toast.makeText(this, getString(R.string.toast_importing), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                ModelImportManager.importFromUri(this@LibraryActivity, uri)
            }
            if (imported == null) {
                Toast.makeText(this@LibraryActivity,
                    getString(R.string.toast_import_failed), Toast.LENGTH_LONG).show()
                return@launch
            }

            if (!ModelImportManager.verifyImport(this@LibraryActivity, imported)) {
                Toast.makeText(this@LibraryActivity,
                    getString(R.string.toast_import_verify_failed), Toast.LENGTH_LONG).show()
                ModelImportManager.deleteImported(this@LibraryActivity, imported)
                return@launch
            }

            if (allModels.none { it.modelId == imported.modelId }) {
                allModels.add(imported)
                adapter.updateItems(allModels.toList())
            }

            Toast.makeText(this@LibraryActivity,
                getString(R.string.toast_import_added, imported.name), Toast.LENGTH_SHORT).show()
        }
    }


    // ── Export state ──────────────────────────────────────────────────────────
    private var pendingExportItem: ModelItem?    = null
    private var pendingExportBytes: ByteArray?   = null

    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("model/gltf-binary")
    ) { uri ->
        uri ?: run { pendingExportBytes = null; pendingExportItem = null; return@registerForActivityResult }
        val bytes = pendingExportBytes ?: return@registerForActivityResult
        pendingExportBytes = null
        pendingExportItem  = null

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LibraryActivity,
                        getString(R.string.toast_export_success), Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LibraryActivity,
                        getString(R.string.toast_export_failed, e.message ?: ""), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

// ── Export flow ───────────────────────────────────────────────────────────

    private fun startExportFlow(item: ModelItem) {
        val profiles = profileManager.listExportableProfiles(item.modelId)

        if (profiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_profile_to_export), Toast.LENGTH_SHORT).show()
            return
        }

        ExportProfilePickerDialog.Companion.newInstance(item.modelId, item.name).apply {
            onProfileSelected = { _, profile ->
                launchExportWithProfile(item, profile)
            }
        }.show(supportFragmentManager, "export_profile")
    }

    private fun launchExportWithProfile(item: ModelItem, profile: ModelProfile?) {
        pendingExportItem = item

        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                if (profile == null) {
                    if (item.isAsset) {
                        runCatching {
                            assets.open(item.modelPath).use { it.readBytes() }
                        }.getOrNull()
                    } else {
                        runCatching {
                            File(item.modelPath).readBytes()
                        }.getOrNull()
                    }
                } else {
                    if (item.isAsset) {
                        val raw = runCatching {
                            assets.open(item.modelPath).use { it.readBytes() }
                        }.getOrNull() ?: return@withContext null
                        GlbTransformExporter.applyProfileToBytes(raw, item.modelPath, profile)
                    } else {
                        GlbTransformExporter.applyProfileToGlb(File(item.modelPath), profile)
                    }
                }
            }

            if (bytes == null) {
                Toast.makeText(this@LibraryActivity,
                    getString(R.string.toast_export_prepare_failed), Toast.LENGTH_LONG).show()
                pendingExportItem = null
                return@launch
            }
            pendingExportBytes = bytes
            exportFileLauncher.launch("${item.name}.glb")
        }
    }

    // ── Rename flow ───────────────────────────────────────────────────────────

    private fun showRenameDialog(item: ModelItem) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_input, null)
        val input = view.findViewById<EditText>(R.id.etRenameInput).apply {
            setText(item.name)
            selectAll()
            filters = arrayOf(InputFilter.LengthFilter(255))
            hint = getString(R.string.dialog_rename_hint)
        }

        view.findViewById<TextView>(R.id.tvDialogTitle).text = getString(R.string.dialog_rename_title)
        view.findViewById<Button>(R.id.btnConfirm).text = getString(R.string.confirm)


        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            renameModelAsync(item, input.text.toString())
            dialog.dismiss()
        }

        dialog.show()

        input.post {
            input.setSelection(input.text.length)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    private fun renameModelAsync(item: ModelItem, newName: String) {
        lifecycleScope.launch {
            val renamed = withContext(Dispatchers.IO) {
                ModelImportManager.renameModel(this@LibraryActivity, item, newName)
            }

            if (renamed == null) {
                val reason = when {
                    newName.isBlank()       -> getString(R.string.error_name_blank)
                    else                    -> getString(R.string.error_name_exists)
                }
                Toast.makeText(this@LibraryActivity,
                    getString(R.string.toast_rename_failed, reason), Toast.LENGTH_LONG).show()
                return@launch
            }

            val idx = allModels.indexOfFirst { it.modelId == item.modelId }
            if (idx != -1) {
                allModels[idx] = renamed
                adapter.updateItems(allModels.toList())
            }

            if (selectedModelKey == item.modelId) {
                selectedModelKey = renamed.modelId
                adapter.updateSelection(selectedModelKey)
            }

            Toast.makeText(this@LibraryActivity,
                getString(R.string.toast_rename_success, item.name, renamed.name), Toast.LENGTH_SHORT).show()
        }
    }
    private fun refreshAllModels() {
        allModels.replaceAll { item ->
            val profileTimestamp = profileManager.getLastSavedTime(item.modelId)
            if (profileTimestamp > 0L) item.copy(lastModified = profileTimestamp)
            else item
        }
        adapter.updateItems(allModels.toList())
    }

    private fun wireFilterChips() {
        val chipAlphabetical = findViewById<Chip>(R.id.chipAlphabetical)
        val chipRecent       = findViewById<Chip>(R.id.chipRecent)
        val chipSize         = findViewById<Chip>(R.id.chipSize)
        val chipImported     = findViewById<Chip>(R.id.chipImported)
        val chipSaved        = findViewById<Chip>(R.id.chipSaved)

        fun syncChips() {
            chipAlphabetical.isChecked = filterManager.current == LibraryFilterManager.Filter.ALPHABETICAL
            chipRecent.isChecked       = filterManager.current == LibraryFilterManager.Filter.RECENT
            chipSize.isChecked         = filterManager.current == LibraryFilterManager.Filter.SIZE

            chipAlphabetical.chipIcon = getDrawable(
                if (filterManager.current == LibraryFilterManager.Filter.ALPHABETICAL && !filterManager.ascending)
                    R.drawable.ic_sort_asc else R.drawable.ic_sort_desc
            )
            chipRecent.chipIcon = getDrawable(
                if (filterManager.current == LibraryFilterManager.Filter.RECENT && filterManager.ascending)
                    R.drawable.ic_sort_asc else R.drawable.ic_sort_desc
            )
            chipSize.chipIcon = getDrawable(
                if (filterManager.current == LibraryFilterManager.Filter.SIZE && filterManager.ascending)
                    R.drawable.ic_sort_asc else R.drawable.ic_sort_desc
            )

            chipImported.isChecked = filterManager.activeSubset == LibraryFilterManager.Filter.IMPORTED
            chipSaved.isChecked    = filterManager.activeSubset == LibraryFilterManager.Filter.PROFILE
        }

        fun applyAndRefresh() {
            val sorted = filterManager.apply(allModels)
            sorted.forEach { log("${it.name}: ${it.sizeBytes} bytes") }
            adapter.updateItems(sorted)
            updateCountLabel()
            syncChips()
        }

        findViewById<Chip>(R.id.chipAll).setOnClickListener {
            if (filterManager.activeSubset != null) {
                filterManager.reset()
                applyAndRefresh()
            }
        }
        chipAlphabetical.setOnClickListener {
            filterManager.selectSort(LibraryFilterManager.Filter.ALPHABETICAL)
            applyAndRefresh()
        }
        chipRecent.setOnClickListener {
            filterManager.selectSort(LibraryFilterManager.Filter.RECENT)
            applyAndRefresh()
        }
        chipSize.setOnClickListener {
            filterManager.selectSort(LibraryFilterManager.Filter.SIZE)
            applyAndRefresh()
        }
        chipImported.setOnClickListener {
            filterManager.selectSubset(LibraryFilterManager.Filter.IMPORTED)
            applyAndRefresh()
        }
        chipSaved.setOnClickListener {
            filterManager.selectSubset(LibraryFilterManager.Filter.PROFILE)
            applyAndRefresh()
        }

        syncChips()
    }


    private fun updateCountLabel() {
        val count = filterManager.apply(allModels).size
        findViewById<TextView>(R.id.tvItemCount)
            .text = getString(R.string.library_item_count, count)
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor.use { c ->
                if (c != null && c.moveToFirst()) {
                    val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = c.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    private fun deleteImportedModel(item: ModelItem) {
        if (ModelImportManager.deleteImported(this, item)) {
            allModels.remove(item)
            adapter.updateItems(allModels.toList())
            Toast.makeText(this, getString(R.string.toast_delete_success, item.name), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_delete_failed, item.name), Toast.LENGTH_SHORT).show()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        SplitInstallManagerFactory.create(this).unregisterListener(splitInstallListener)
    }
}