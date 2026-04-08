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
import com.example.pracazaliczeniowa.Helpers.ThumbnailCaptureHelper

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

    // Refresh the grid when returning from the preview (thumbnails may have changed)
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
            items         = models,
            savedProfiles = savedProfiles
        ) { selectedModel ->
            val intent = Intent(this, ModelPreviewActivity::class.java).apply {
                putExtra(ModelPreviewActivity.EXTRA_MODEL_PATH, selectedModel.modelPath)
            }
            previewLauncher.launch(intent)
        }

        recyclerView.adapter = adapter

        // Pre-cache thumbnails for any models that don't have one yet.
        // The hidden SceneView renders each model off-screen at a fixed
        // isometric angle and saves a 256×256 PNG to filesDir/thumbnails/.
        // The adapter is refreshed automatically when all captures finish.
        ThumbnailCaptureHelper(
            context   = this,
            models    = models,
            onAllDone = { adapter.notifyDataSetChanged() }
        ).start()
    }
}
