package com.example.WymiAR.Managers

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


@Serializable
data class ModelProfileBundle(
    val default: ModelProfile? = null,
    val named: Map<String, ModelProfile> = emptyMap(),
    val colorOverride: Int? = null
)


sealed class ProfileSaveResult {
    object Success : ProfileSaveResult()
    object TooManyProfiles : ProfileSaveResult()
    data class Error(val cause: Exception) : ProfileSaveResult()
}



class ProfileManager(context: Context) {

    companion object {
        const val MAX_NAMED = 5
    }

    private val folder = File(context.filesDir, "profiles")
    private val json   = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        if (!folder.exists()) folder.mkdirs()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun fileFor(modelUuid: String) = File(folder, "$modelUuid.json")

    private fun loadBundle(modelName: String): ModelProfileBundle {
        val file = fileFor(modelName)
        if (!file.exists()) return ModelProfileBundle()
        return try {
            json.decodeFromString<ModelProfileBundle>(file.readText())
        } catch (e: Exception) {
            ModelProfileBundle()
        }
    }

    private fun saveBundle(modelId: String, bundle: ModelProfileBundle) {
        fileFor(modelId).writeText(json.encodeToString(bundle))
    }

    fun loadDefault(modelId: String): ModelProfile? =
        loadBundle(modelId).default

    fun saveDefault(modelId: String, profile: ModelProfile) {
        val bundle = loadBundle(modelId)
        saveBundle(modelId, bundle.copy(default = profile))
    }

    fun listNamedProfiles(modelId: String): List<String> =
        loadBundle(modelId).named.keys.toList()

    fun loadNamed(modelId: String, slotName: String): ModelProfile? =
        loadBundle(modelId).named[slotName]

    fun listExportableProfiles(modelId: String): List<Pair<String, ModelProfile>> {
        val bundle = loadBundle(modelId)
        val result = mutableListOf<Pair<String, ModelProfile>>()
        bundle.default?.let { result.add("Default" to it) }
        bundle.named.forEach { (name, profile) -> result.add(name to profile) }
        return result
    }
    fun saveNamed(
        modelId: String,
        slotName: String,
        profile: ModelProfile
    ): ProfileSaveResult {
        return try {
            val bundle   = loadBundle(modelId)
            val isNewSlot = slotName !in bundle.named

            if (isNewSlot && bundle.named.size >= MAX_NAMED) {
                return ProfileSaveResult.TooManyProfiles
            }

            val updatedNamed = bundle.named.toMutableMap()
            updatedNamed[slotName] = profile
            saveBundle(modelId, bundle.copy(named = updatedNamed))
            ProfileSaveResult.Success
        } catch (e: Exception) {
            ProfileSaveResult.Error(e)
        }
    }

    fun deleteNamed(modelId: String, slotName: String) {
        val bundle       = loadBundle(modelId)
        val updatedNamed = bundle.named.toMutableMap()
        updatedNamed.remove(slotName)
        saveBundle(modelId, bundle.copy(named = updatedNamed))
    }

    // ── Colour override ──────────────────────────────────────────────────────

    fun loadColorOverride(modelId: String): Int? =
        loadBundle(modelId).colorOverride

    fun saveColorOverride(modelId: String, color: Int) {
        val bundle = loadBundle(modelId)
        saveBundle(modelId, bundle.copy(colorOverride = color))
    }

    fun clearColorOverride(modelId: String) {
        val bundle = loadBundle(modelId)
        saveBundle(modelId, bundle.copy(colorOverride = null))
    }

    fun hasAnyProfile(modelId: String): Boolean {
        val bundle = loadBundle(modelId)
        return bundle.default != null || bundle.named.isNotEmpty()
    }

    fun getLastSavedTime(modelId: String): Long {
        val file = fileFor(modelId)
        return if (file.exists()) file.lastModified() else 0L
    }
}