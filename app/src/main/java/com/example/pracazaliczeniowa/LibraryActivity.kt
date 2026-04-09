package com.example.pracazaliczeniowa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.example.pracazaliczeniowa.Helpers.ModelItem
import com.example.pracazaliczeniowa.Helpers.ModelLibraryAdapter
import com.example.pracazaliczeniowa.Helpers.ProfileManager

class LibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH = "extra_model_path"
    }

    // Reloads this activity when SettingsActivity signals a theme change
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) recreate()
    }

    private var selectedModelKey: String? = null

    private val arLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        adapter.notifyDataSetChanged()
    }

    // Refresh the grid when returning from ModelPreviewActivity
    // (the user may have saved a new thumbnail)
    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        adapter.notifyDataSetChanged()
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ModelLibraryAdapter

    private val models = listOf(
        ModelItem("Cat", "models/cat.glb", R.drawable.ic_model_placeholder),
        ModelItem("Dog", "models/dog.glb", R.drawable.ic_model_placeholder),
        ModelItem("Van", "models/van.glb", R.drawable.ic_model_placeholder),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        val profileManager = ProfileManager(this)
        val savedProfiles = models
            .filter { profileManager.hasAnyProfile(it.profileKey) }
            .map    { it.profileKey }
            .toSet()

        recyclerView = findViewById(R.id.rvModelLibrary)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        adapter = ModelLibraryAdapter(
            items          = models,
            savedProfiles  = savedProfiles,
            selectedKey    = selectedModelKey,
            onItemClick    = { selectedModel ->
                selectedModelKey = selectedModel.profileKey
                adapter.updateSelection(selectedModelKey)

                val intent = Intent(this, ARActivity::class.java).apply {
                    putExtra("extra_model_path", selectedModel.modelPath)
                }
                arLauncher.launch(intent)
            },
            onPreviewClick = { selectedModel ->
                val intent = Intent(this, ModelPreviewActivity::class.java).apply {
                    putExtra(ModelPreviewActivity.EXTRA_MODEL_PATH, selectedModel.modelPath)
                    putExtra(ModelPreviewActivity.EXTRA_PROFILE_KEY, selectedModel.profileKey)
                }
                previewLauncher.launch(intent)
            }
        )

        recyclerView.adapter = adapter
        // No background thumbnail capture needed — pre-installed models use their
        // bundled drawable fallback, and user-added models get thumbnails via the
        // "Preview → Set Thumbnail" flow in ModelPreviewActivity.
    }
}
