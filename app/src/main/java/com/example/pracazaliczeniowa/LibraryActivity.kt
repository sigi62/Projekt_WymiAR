package com.example.pracazaliczeniowa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pracazaliczeniowa.Helpers.ExportProfilePickerDialog
import com.example.pracazaliczeniowa.Helpers.GlbTransformExporter
import com.example.pracazaliczeniowa.Helpers.ModelImportManager
import com.example.pracazaliczeniowa.Helpers.ModelItem
import com.example.pracazaliczeniowa.Helpers.ModelLibraryManager
import com.example.pracazaliczeniowa.Helpers.ProfileManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.pracazaliczeniowa.Helpers.LibraryFilterManager
import com.example.pracazaliczeniowa.Helpers.ModelFileUtils
import com.example.pracazaliczeniowa.Helpers.ModelProfile

class LibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH     = "extra_model_path"
        const val EXTRA_MODEL_IS_ASSET = "extra_model_is_asset"
    }


    val modelMimeTypes = arrayOf(
        "model/obj",
        "model/stl",                  // STL — may not be recognised by all pickers
        "application/sla",            // STL alternative MIME (most common on Android)
        "application/vnd.ms-pki.stl", // STL second alternative used by some OEM pickers
        "model/gltf-binary",          // GLB
        "model/gltf+json",            // GLTF text
        "model/fbx",                  // FBX — not formally registered; may not be honoured by all pickers
        "model/vnd.collada+xml",      // DAE (Collada)
        "image/x-3ds",                // 3DS — non-standard but used by some file managers
        "application/octet-stream"    // catch-all for binary formats (.fbx, .ply, .3ds, .stl, etc.)
    )

    // ── Result launchers ──────────────────────────────────────────────────────

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) recreate()
    }

    private val arLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { adapter.notifyDataSetChanged() }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Refresh items so lastModified reflects any profile save that just happened
        refreshAllModels()
        adapter.notifyDataSetChanged()
    }
    // File picker — opened only after converter is confirmed installed (if needed)
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
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


    // SplitInstall listener — kept as a field so we can unregister it


    private val splitInstallListener = SplitInstallStateUpdatedListener { state ->
        when (state.status()) {
            SplitInstallSessionStatus.INSTALLED -> {
                Toast.makeText(this, getString(R.string.toast_converter_ready), Toast.LENGTH_SHORT).show()
                importLauncher.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, modelMimeTypes)
                    }
                )
            }
            SplitInstallSessionStatus.FAILED -> {
                Toast.makeText(this, getString(R.string.toast_converter_failed, state.errorCode().toString()), Toast.LENGTH_LONG).show()
            }
            SplitInstallSessionStatus.DOWNLOADING -> {
                Toast.makeText(this, getString(R.string.toast_converter_downloading), Toast.LENGTH_SHORT).show()
            }
            else -> { /* PENDING, REQUIRES_USER_CONFIRMATION, etc. — ignore */ }
        }
    }

    // ── Bundled models ────────────────────────────────────────────────────────
    private val installTime: Long by lazy {
        packageManager
            .getPackageInfo(packageName, 0)
            .firstInstallTime
    }


    private val bundledModels: List<ModelItem> by lazy {
        listOf(
            ModelItem("Cat", "models/cat.glb", R.drawable.ic_model_placeholder,
                isAsset = true, createdAt = installTime,
                defaultSizeM = ModelFileUtils.readBounds(this,"models/cat.glb")),   // ~25×20×30 cm — adjust to reality
            ModelItem("Dog", "models/dog.glb", R.drawable.ic_model_placeholder,
                isAsset = true, createdAt = installTime,
                defaultSizeM = ModelFileUtils.readBounds(this,"models/dog.glb")),
            ModelItem("Van", "models/van.glb", R.drawable.ic_model_placeholder,
                isAsset = true, createdAt = installTime,
                defaultSizeM = ModelFileUtils.readBounds(this,"models/van.glb"))
        )
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

        // Import button — always opens picker; converter is installed on-demand
        // only if the picked file actually needs conversion (see importLauncher above
        // and ensureConverterThenPick below).
        findViewById<ImageButton>(R.id.btnImportModel).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"                         // required — some pickers ignore it without this
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
        val savedProfiles = allModels
            .filter { profileManager.hasAnyProfile(it.profileKey) }
            .map    { it.profileKey }
            .toSet()

        filterManager = LibraryFilterManager(savedProfiles)
        wireFilterChips()

        recyclerView = findViewById(R.id.rvModelLibrary)
        recyclerView.layoutManager = GridLayoutManager(this, 2)


        adapter = ModelLibraryManager(
            items            = allModels.toList(),
            savedProfiles    = savedProfiles,
            selectedKey      = selectedModelKey,
            onItemClick      = { selectedModel ->
                selectedModelKey = selectedModel.profileKey
                adapter.updateSelection(selectedModelKey)
                arLauncher.launch(
                    Intent(this, ARActivity::class.java).apply {
                        putExtra(EXTRA_MODEL_PATH,     selectedModel.modelPath)
                        putExtra(EXTRA_MODEL_IS_ASSET, selectedModel.isAsset)
                    }
                )
            },
            onPreviewClick   = { selectedModel ->
                previewLauncher.launch(
                    Intent(this, ModelPreviewActivity::class.java).apply {
                        putExtra(ModelPreviewActivity.EXTRA_MODEL_PATH,     selectedModel.modelPath)
                        putExtra(ModelPreviewActivity.EXTRA_MODEL_IS_ASSET, selectedModel.isAsset)
                        putExtra(ModelPreviewActivity.EXTRA_PROFILE_KEY,    selectedModel.profileKey)
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

    /**
     * Called after the user picks a file.
     *
     * • GLB  → import directly (no converter needed).
     * • OBJ/STL → if converter module is installed, convert on a background
     *             thread; otherwise download the module first, then re-open
     *             the picker so the user can pick the same file again.
     */
    private fun processImport(uri: Uri) {
        val fileName = ModelImportManager.resolveFileName(this, uri) ?: ""
        val ext      = fileName.substringAfterLast('.', "").lowercase()

        // Any format other than .glb needs the native converter module.
        val needsConvert = ext != "glb"

        if (!needsConvert) {
            // GLB — direct copy, no converter module required.
            runImportAsync(uri)
            return
        }

        // All other formats (.obj, .stl, .fbx, .dae, .gltf, .3ds, .ply) —
        // make sure the converter module is present first.
        val manager = SplitInstallManagerFactory.create(this)
        if (manager.installedModules.contains("converter")) {
            runImportAsync(uri)
        } else {
            // Module not installed — download it.
            // The SplitInstallStateUpdatedListener registered in onCreate will
            // open the picker again once INSTALLED fires. We can't reuse the
            // current URI across the module install because the content
            // resolver grant may expire, so we ask the user to pick again.
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

    /**
     * Runs [ModelImportManager.importFromUri] on an IO thread so the main
     * thread is never blocked during file copy or native conversion.
     */
    private fun runImportAsync(uri: Uri) {
        Toast.makeText(this, getString(R.string.toast_importing), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                ModelImportManager.importFromUri(this@LibraryActivity, uri)
            }

            // Back on main thread:
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

            if (allModels.none { it.profileKey == imported.profileKey }) {
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
                    Toast.makeText(this@LibraryActivity,
                        getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LibraryActivity,
                        getString(R.string.toast_export_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

// ── Export flow ───────────────────────────────────────────────────────────

    private fun startExportFlow(item: ModelItem) {
        val profiles = profileManager.listExportableProfiles(item.profileKey)

        if (profiles.isEmpty()) {
            // Shouldn't normally be reachable since the menu item is hidden,
            // but guard just in case.
            Toast.makeText(this, getString(R.string.toast_no_profile_to_export), Toast.LENGTH_SHORT).show()
            return
        }

        ExportProfilePickerDialog.newInstance(item.profileKey, item.name).apply {
            onProfileSelected = { _, profile ->
                launchExportWithProfile(item, profile)
            }
        }.show(supportFragmentManager, "export_profile")
    }

    /**
     * Applies [profile] to the GLB on a background thread, then opens the
     * system file picker so the user can choose where to save the result.
     * Pass null to export the file unchanged.
     */
    private fun launchExportWithProfile(item: ModelItem, profile: ModelProfile?) {
        pendingExportItem = item

        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                if (profile == null) {
                    // No transform — just read the raw bytes
                    if (item.isAsset) {
                        runCatching {
                            assets.open(item.modelPath).use { it.readBytes() }
                        }.getOrNull()
                    } else {
                        runCatching {
                            java.io.File(item.modelPath).readBytes()
                        }.getOrNull()
                    }
                } else {
                    if (item.isAsset) {
                        val raw = runCatching {
                            assets.open(item.modelPath).use { it.readBytes() }
                        }.getOrNull() ?: return@withContext null
                        GlbTransformExporter.applyProfileToBytes(raw, item.modelPath, profile)
                    } else {
                        GlbTransformExporter.applyProfileToGlb(java.io.File(item.modelPath), profile)
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

    /**
     * Shows an [AlertDialog] with an [EditText] pre-filled with the current
     * model name. On confirmation, renames the file on a background thread
     * and refreshes the grid on success.
     */
    private fun showRenameDialog(item: ModelItem) {
        val input = EditText(this).apply {
            inputType  = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(item.name)
            selectAll()
            // Reasonable upper bound; filesystem limits vary but 255 chars is safe
            filters = arrayOf(InputFilter.LengthFilter(255))
            hint    = getString(R.string.dialog_rename_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_rename_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_rename)) { _, _ ->
                val newName = input.text.toString()
                renameModelAsync(item, newName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        // Move cursor to the end and open the keyboard automatically
        input.post {
            input.setSelection(input.text.length)
            val imm = getSystemService(INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Calls [ModelImportManager.renameModel] on an IO thread, then updates
     * [allModels] and the adapter on the main thread.
     */
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

            // Replace the old item in allModels with the renamed one
            val idx = allModels.indexOfFirst { it.profileKey == item.profileKey }
            if (idx != -1) {
                allModels[idx] = renamed
                adapter.updateItems(allModels.toList())
            }

            // Keep selection in sync if the renamed model was selected
            if (selectedModelKey == item.profileKey) {
                selectedModelKey = renamed.profileKey
                adapter.updateSelection(selectedModelKey)
            }

            Toast.makeText(this@LibraryActivity,
                getString(R.string.toast_rename_success, item.name, renamed.name), Toast.LENGTH_SHORT).show()
        }
    }
    private fun refreshAllModels() {
        allModels.replaceAll { item ->
            val profileTimestamp = profileManager.getLastSavedTime(item.profileKey) // you may need to add this to ProfileManager
            if (profileTimestamp > 0L) item.copy(lastModified = profileTimestamp)
            else item
        }
        adapter.updateItems(allModels.toList())
    }

    private fun wireFilterChips() {
        // Chips that carry a direction icon (togglable)
        val chipAlphabetical = findViewById<com.google.android.material.chip.Chip>(R.id.chipAlphabetical)
        val chipRecent       = findViewById<com.google.android.material.chip.Chip>(R.id.chipRecent)

        // Simple chips — no direction icon
        val simpleChips = mapOf(
            R.id.chipAll      to LibraryFilterManager.Filter.ALL,
            R.id.chipImported to LibraryFilterManager.Filter.IMPORTED,
            R.id.chipSaved    to LibraryFilterManager.Filter.SAVED
        )

        fun syncChipIcons() {
            // Alphabetical: show A→Z (ascending) or Z→A (descending)
            chipAlphabetical.chipIcon = getDrawable(
                if (filterManager.current == LibraryFilterManager.Filter.ALPHABETICAL && !filterManager.ascending)
                    R.drawable.ic_sort_asc
                else
                    R.drawable.ic_sort_desc
            )
            // Recent: show arrow-down (newest first / descending) or arrow-up (oldest first)
            chipRecent.chipIcon = getDrawable(
                if (filterManager.current == LibraryFilterManager.Filter.RECENT && filterManager.ascending)
                    R.drawable.ic_sort_asc
                else
                    R.drawable.ic_sort_desc
            )
        }

        fun applyAndRefresh() {
            adapter.updateItems(filterManager.apply(allModels))
            updateCountLabel()
            syncChipIcons()
        }

        chipAlphabetical.setOnClickListener {
            filterManager.select(LibraryFilterManager.Filter.ALPHABETICAL)
            applyAndRefresh()
        }

        chipRecent.setOnClickListener {
            filterManager.select(LibraryFilterManager.Filter.RECENT)
            applyAndRefresh()
        }

        simpleChips.forEach { (id, filter) ->
            findViewById<com.google.android.material.chip.Chip>(id).setOnClickListener {
                filterManager.select(filter)
                applyAndRefresh()
            }
        }

        // Initialise icons to match the default state (RECENT descending)
        syncChipIcons()
    }

    private fun updateCountLabel() {
        val count = filterManager.apply(allModels).size
        findViewById<android.widget.TextView>(R.id.tvItemCount)
            .text = getString(R.string.library_item_count, count)
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor.use { c ->
                if (c != null && c.moveToFirst()) {
                    val index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
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

    // ── Delete helper ─────────────────────────────────────────────────────────

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