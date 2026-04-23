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
import com.example.pracazaliczeniowa.Helpers.ModelImportManager
import com.example.pracazaliczeniowa.Helpers.ModelItem
import com.example.pracazaliczeniowa.Helpers.ModelLibraryAdapter
import com.example.pracazaliczeniowa.Helpers.ProfileManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH     = "extra_model_path"
        const val EXTRA_MODEL_IS_ASSET = "extra_model_is_asset"
    }

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
    ) { adapter.notifyDataSetChanged() }

    // File picker — opened only after converter is confirmed installed (if needed)
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        processImport(uri)
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var selectedModelKey: String? = null
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ModelLibraryAdapter
    private val allModels = mutableListOf<ModelItem>()

    // SplitInstall listener — kept as a field so we can unregister it
    private val splitInstallListener = SplitInstallStateUpdatedListener { state ->
        when (state.status()) {
            SplitInstallSessionStatus.INSTALLED -> {
                Toast.makeText(this, "Converter ready!", Toast.LENGTH_SHORT).show()
                // Now that the module is installed, open the file picker
                importLauncher.launch(arrayOf("*/*"))
            }
            SplitInstallSessionStatus.FAILED -> {
                Toast.makeText(
                    this,
                    "Failed to download converter (error ${state.errorCode()})",
                    Toast.LENGTH_LONG
                ).show()
            }
            SplitInstallSessionStatus.DOWNLOADING -> {
                Toast.makeText(this, "Downloading converter…", Toast.LENGTH_SHORT).show()
            }
            else -> { /* PENDING, REQUIRES_USER_CONFIRMATION, etc. — ignore */ }
        }
    }

    // ── Bundled models ────────────────────────────────────────────────────────

    private val bundledModels = listOf(
        ModelItem("Cat", "models/cat.glb", R.drawable.ic_model_placeholder, isAsset = true),
        ModelItem("Dog", "models/dog.glb", R.drawable.ic_model_placeholder, isAsset = true),
        ModelItem("Van", "models/van.glb", R.drawable.ic_model_placeholder, isAsset = true),
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
            // We don't know yet what file the user will pick, so open the picker
            // unconditionally. If the picked file is OBJ/STL and the converter
            // isn't installed, processImport will trigger the download then retry.
            importLauncher.launch(arrayOf("*/*"))
        }

        allModels.clear()
        allModels.addAll(bundledModels)
        allModels.addAll(ModelImportManager.loadImported(this).also { imports ->
            imports.forEach { ModelImportManager.verifyImport(this, it) }
        })

        val profileManager = ProfileManager(this)
        val savedProfiles = allModels
            .filter { profileManager.hasAnyProfile(it.profileKey) }
            .map    { it.profileKey }
            .toSet()

        recyclerView = findViewById(R.id.rvModelLibrary)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        adapter = ModelLibraryAdapter(
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
            onRenameImported = { showRenameDialog(it) }
        )

        recyclerView.adapter = adapter
    }

    override fun onDestroy() {
        super.onDestroy()
        SplitInstallManagerFactory.create(this).unregisterListener(splitInstallListener)
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
        val fileName      = ModelImportManager.resolveFileName(this, uri) ?: ""
        val needsConvert  = fileName.endsWith(".obj", ignoreCase = true) ||
                fileName.endsWith(".stl", ignoreCase = true)

        if (!needsConvert) {
            // Standard GLB path — fast, fine on the main thread briefly,
            // but we still dispatch to IO to avoid ANR on large files.
            runImportAsync(uri)
            return
        }

        // OBJ / STL — make sure the converter module is present first.
        val manager = SplitInstallManagerFactory.create(this)
        if (manager.installedModules.contains("converter")) {
            runImportAsync(uri)
        } else {
            // Module not installed — download it.
            // The SplitInstallStateUpdatedListener registered in onCreate will
            // open the picker again once INSTALLED fires. We can't reuse the
            // current URI across the module install because the content
            // resolver grant may expire, so we ask the user to pick again.
            Toast.makeText(this, "Downloading 3D converter…", Toast.LENGTH_SHORT).show()
            val request = SplitInstallRequest.newBuilder()
                .addModule("converter")
                .build()
            manager.startInstall(request)
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    /**
     * Runs [ModelImportManager.importFromUri] on an IO thread so the main
     * thread is never blocked during file copy or native conversion.
     */
    private fun runImportAsync(uri: Uri) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                ModelImportManager.importFromUri(this@LibraryActivity, uri)
            }

            // Back on main thread:
            if (imported == null) {
                Toast.makeText(this@LibraryActivity,
                    "Import failed – unsupported or corrupt file", Toast.LENGTH_LONG).show()
                return@launch
            }

            if (!ModelImportManager.verifyImport(this@LibraryActivity, imported)) {
                Toast.makeText(this@LibraryActivity,
                    "File imported but verification failed", Toast.LENGTH_LONG).show()
                ModelImportManager.deleteImported(this@LibraryActivity, imported)
                return@launch
            }

            if (allModels.none { it.profileKey == imported.profileKey }) {
                allModels.add(imported)
                adapter.updateItems(allModels.toList())
            }

            Toast.makeText(this@LibraryActivity,
                "${imported.name} added to library", Toast.LENGTH_SHORT).show()
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
            hint    = "Model name"
        }

        AlertDialog.Builder(this)
            .setTitle("Rename model")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString()
                renameModelAsync(item, newName)
            }
            .setNegativeButton("Cancel", null)
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
                    newName.isBlank()       -> "Name cannot be blank."
                    else                    -> "A model with that name may already exist."
                }
                Toast.makeText(this@LibraryActivity,
                    "Rename failed. $reason", Toast.LENGTH_LONG).show()
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
                "'${item.name}' renamed to '${renamed.name}'", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Delete helper ─────────────────────────────────────────────────────────

    private fun deleteImportedModel(item: ModelItem) {
        if (ModelImportManager.deleteImported(this, item)) {
            allModels.remove(item)
            adapter.updateItems(allModels.toList())
            Toast.makeText(this, "${item.name} removed", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Could not delete ${item.name}", Toast.LENGTH_SHORT).show()
        }
    }
}