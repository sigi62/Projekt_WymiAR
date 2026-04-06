package com.example.pracazaliczeniowa

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LibraryActivity : AppCompatActivity() {

    companion object {
        /** Intent extra key used to pass the chosen model path to ARActivity. */
        const val EXTRA_MODEL_PATH = "extra_model_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        // ----------------------------------------------------------------
        // 1. Define your available models here.
        //    Add one ModelItem per .glb file you ship in assets/models/.
        //    thumbnailRes is optional – set to null and a placeholder will
        //    be used, or point to a res/drawable image (e.g. R.drawable.thumb_cat).
        // ----------------------------------------------------------------
        val models = listOf(
            ModelItem("Cat",   "models/cat.glb",   R.drawable.ic_model_placeholder),
            ModelItem("Dog", "models/dog.glb", R.drawable.ic_model_placeholder),
            ModelItem("Van", "models/van.glb", R.drawable.ic_model_placeholder),   // no thumbnail → placeholder
        )

        // ----------------------------------------------------------------
        // 2. Ask ProfileManager which models already have a saved profile
        //    so the adapter can show the "✓ Saved" badge.
        // ----------------------------------------------------------------
        val profileManager = ProfileManager(this)
        val savedProfiles  = models
            .filter { profileManager.hasAnyProfile(it.profileKey) }
            .map    { it.profileKey }
            .toSet()

        // ----------------------------------------------------------------
        // 3. Wire up the RecyclerView grid.
        // ----------------------------------------------------------------
        val recyclerView = findViewById<RecyclerView>(R.id.rvModelLibrary)
        recyclerView.layoutManager = GridLayoutManager(this, 2)   // 2-column grid
        recyclerView.adapter = ModelLibraryAdapter(
            items         = models,
            savedProfiles = savedProfiles
        ) { selectedModel ->
            // Launch ARActivity with the chosen model path
            val intent = Intent(this, ARActivity::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, selectedModel.modelPath)
            }
            startActivity(intent)
        }
    }
}
