package com.example.pracazaliczeniowa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.example.pracazaliczeniowa.Helpers.ModelImportManager
import com.example.pracazaliczeniowa.Helpers.ModelItem
import com.example.pracazaliczeniowa.Helpers.ModelLibraryAdapter
import com.example.pracazaliczeniowa.Helpers.ProfileManager

class LibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH = "extra_model_path"
        const val EXTRA_MODEL_IS_ASSET = "extra_model_is_asset"
    }

    // ── Result launchers ──────────────────────────────────────────────────────

    // Reloads this activity when SettingsActivity signals a theme change
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) recreate()
    }

    private val arLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        adapter.notifyDataSetChanged()
    }

    // Refresh the grid when returning from ModelPreviewActivity
    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        adapter.notifyDataSetChanged()
    }

    // File picker for importing .glb models
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        val imported = ModelImportManager.importFromUri(this, uri) ?: run {
            Toast.makeText(this, "Import failed – please select a valid .glb file", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

// verify before adding to library
        if (!ModelImportManager.verifyImport(this, imported)) {
            Toast.makeText(this, "File imported but may be corrupted", Toast.LENGTH_LONG).show()
            ModelImportManager.deleteImported(this, imported)
            return@registerForActivityResult
        }

        // Avoid adding duplicate if the file was already imported
        if (allModels.none { it.profileKey == imported.profileKey }) {
            allModels.add(imported)
            adapter.updateItems(allModels.toList())
            Toast.makeText(this, "Imported ${imported.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "${imported.name} is already in your library", Toast.LENGTH_SHORT).show()
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var selectedModelKey: String? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ModelLibraryAdapter

    /** Combined list: bundled assets first, then user-imported models. */
    private val allModels = mutableListOf<ModelItem>()

    // ── Bundled (asset) models ─────────────────────────────────────────────────
    private val bundledModels = listOf(
        ModelItem("Cat", "models/cat.glb", R.drawable.ic_model_placeholder, isAsset = true),
        ModelItem("Dog", "models/dog.glb", R.drawable.ic_model_placeholder, isAsset = true),
        ModelItem("Van", "models/van.glb", R.drawable.ic_model_placeholder, isAsset = true),
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        // Settings button
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        // Import button – opens the system file picker filtered to .glb
        findViewById<ImageButton>(R.id.btnImportModel).setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))  // use "*/*" for broadest compat;
            // the user should pick a .glb file
        }

        // Build the combined model list

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
            items          = allModels.toList(),
            savedProfiles  = savedProfiles,
            selectedKey    = selectedModelKey,
            onItemClick    = { selectedModel ->
                selectedModelKey = selectedModel.profileKey
                adapter.updateSelection(selectedModelKey)

                val intent = Intent(this, ARActivity::class.java).apply {
                    putExtra(EXTRA_MODEL_PATH, selectedModel.modelPath)
                    putExtra(EXTRA_MODEL_IS_ASSET, selectedModel.isAsset)
                }
                arLauncher.launch(intent)
            },
            onPreviewClick = { selectedModel ->
                val intent = Intent(this, ModelPreviewActivity::class.java).apply {
                    putExtra(ModelPreviewActivity.EXTRA_MODEL_PATH, selectedModel.modelPath)
                    putExtra(ModelPreviewActivity.EXTRA_MODEL_IS_ASSET, selectedModel.isAsset)
                    putExtra(ModelPreviewActivity.EXTRA_PROFILE_KEY, selectedModel.profileKey)
                }
                previewLauncher.launch(intent)
            },
            onDeleteImported = { modelToDelete ->
                deleteImportedModel(modelToDelete)
            }
        )

        recyclerView.adapter = adapter
    }

    // ── Import / delete helpers ───────────────────────────────────────────────

    private fun deleteImportedModel(item: ModelItem) {
        val deleted = ModelImportManager.deleteImported(this, item)
        if (deleted) {
            allModels.remove(item)
            adapter.updateItems(allModels.toList())
            Toast.makeText(this, "${item.name} removed from library", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Could not delete ${item.name}", Toast.LENGTH_SHORT).show()
        }
    }

}