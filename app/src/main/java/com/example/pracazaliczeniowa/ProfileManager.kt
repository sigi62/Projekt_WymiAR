package com.example.pracazaliczeniowa

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ModelProfile(
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float
)

class ProfileManager(context: Context) {
    private val folder = File(context.filesDir, "profiles")

    init { if (!folder.exists()) folder.mkdirs() }

    fun saveProfile(modelName: String, profile: ModelProfile) {
        val file = File(folder, "$modelName.json")
        file.writeText(Json.encodeToString(profile))
    }

    fun loadProfile(modelName: String): ModelProfile? {
        val file = File(folder, "$modelName.json")
        return if (file.exists()) {
            try {
                Json.decodeFromString<ModelProfile>(file.readText())
            } catch (e: Exception) { null }
        } else null
    }
}