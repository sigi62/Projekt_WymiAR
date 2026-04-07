package com.example.pracazaliczeniowa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH = "extra_model_path"
    }

    // Launcher that recreates this activity if SettingsActivity signals a theme change
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }

        val models = listOf(
            ModelItem("Cat", "models/cat.glb", R.drawable.ic_model_placeholder),
            ModelItem("Dog", "models/dog.glb", R.drawable.ic_model_placeholder),
            ModelItem("Van", "models/van.glb", R.drawable.ic_model_placeholder),
        )

        val profileManager = ProfileManager(this)
        val savedProfiles = models
            .filter { profileManager.hasAnyProfile(it.profileKey) }
            .map { it.profileKey }
            .toSet()

        val recyclerView = findViewById<RecyclerView>(R.id.rvModelLibrary)
        recyclerView.layoutManager = GridLayoutManager(this, 2)
        recyclerView.adapter = ModelLibraryAdapter(
            items         = models,
            savedProfiles = savedProfiles
        ) { selectedModel ->
            val intent = Intent(this, ARActivity::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, selectedModel.modelPath)
            }
            startActivity(intent)
        }
    }
}
