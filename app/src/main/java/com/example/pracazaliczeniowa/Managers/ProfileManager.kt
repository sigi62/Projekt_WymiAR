package com.example.pracazaliczeniowa.Managers

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import java.io.File

// ─────────────────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────────────────

/** Scale + rotation snapshot of a placed model. */
@Serializable
data class ModelProfile(
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    val rotationX: Float,
    val rotationY: Float,
    val rotationZ: Float
)

/**
 * Everything stored for one model in a single JSON file.
 *
 * @param default  The profile that is applied automatically when the model
 *                 is placed.  Null until the user saves one.
 * @param named    Up to [ProfileManager.MAX_NAMED] user-named profiles,
 *                 keyed by the name the user typed.
 */
@Serializable
data class ModelProfileBundle(
    val default: ModelProfile? = null,
    val named: Map<String, ModelProfile> = emptyMap()
)

// ─────────────────────────────────────────────────────────────────────────────
// Sealed result type so callers can show meaningful errors
// ─────────────────────────────────────────────────────────────────────────────

sealed class ProfileSaveResult {
    object Success : ProfileSaveResult()
    object TooManyProfiles : ProfileSaveResult()   // already at MAX_NAMED, slot is new
    data class Error(val cause: Exception) : ProfileSaveResult()
}

// ─────────────────────────────────────────────────────────────────────────────
// ProfileManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Persists model profiles to [Context.filesDir]/profiles/<modelName>.json.
 *
 * Storage lives in the app's private internal storage, so it is never
 * cleared by the OS unless the user explicitly clears app data.
 * The files survive app restarts and updates.
 *
 * Each model gets ONE file that contains:
 *  • a "default" profile (auto-applied on placement, overwritable)
 *  • up to [MAX_NAMED] named profiles chosen by the user
 */
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

    private fun fileFor(modelName: String) = File(folder, "$modelName.json")

    private fun loadBundle(modelName: String): ModelProfileBundle {
        val file = fileFor(modelName)
        if (!file.exists()) return ModelProfileBundle()
        return try {
            json.decodeFromString<ModelProfileBundle>(file.readText())
        } catch (e: Exception) {
            ModelProfileBundle()   // corrupt file → start fresh
        }
    }

    private fun saveBundle(modelName: String, bundle: ModelProfileBundle) {
        fileFor(modelName).writeText(json.encodeToString(bundle))
    }



    // ── Default profile ──────────────────────────────────────────────────────

    /** Returns the default profile, or null if none has been saved yet. */
    fun loadDefault(modelName: String): ModelProfile? =
        loadBundle(modelName).default

    /**
     * Overwrites the default profile.
     * This is what gets applied automatically when the model is placed.
     */
    fun saveDefault(modelName: String, profile: ModelProfile) {
        val bundle = loadBundle(modelName)
        saveBundle(modelName, bundle.copy(default = profile))
    }

    // ── Named profiles ───────────────────────────────────────────────────────

    /** Returns names of all saved named profiles for this model, in insertion order. */
    fun listNamedProfiles(modelName: String): List<String> =
        loadBundle(modelName).named.keys.toList()

    /** Returns a named profile, or null if it doesn't exist. */
    fun loadNamed(modelName: String, slotName: String): ModelProfile? =
        loadBundle(modelName).named[slotName]

    fun listExportableProfiles(modelName: String): List<Pair<String, ModelProfile>> {
        val bundle = loadBundle(modelName)
        val result = mutableListOf<Pair<String, ModelProfile>>()
        bundle.default?.let { result.add("Default" to it) }
        bundle.named.forEach { (name, profile) -> result.add(name to profile) }
        return result
    }
    /**
     * Saves (or overwrites) a named profile.
     * Fails with [ProfileSaveResult.TooManyProfiles] if the slot name is new
     * and the model already has [MAX_NAMED] named profiles.
     */
    fun saveNamed(
        modelName: String,
        slotName: String,
        profile: ModelProfile
    ): ProfileSaveResult {
        return try {
            val bundle   = loadBundle(modelName)
            val isNewSlot = slotName !in bundle.named

            if (isNewSlot && bundle.named.size >= MAX_NAMED) {
                return ProfileSaveResult.TooManyProfiles
            }

            val updatedNamed = bundle.named.toMutableMap()
            updatedNamed[slotName] = profile
            saveBundle(modelName, bundle.copy(named = updatedNamed))
            ProfileSaveResult.Success
        } catch (e: Exception) {
            ProfileSaveResult.Error(e)
        }
    }

    /** Deletes a named profile slot entirely. Does nothing if it doesn't exist. */
    fun deleteNamed(modelName: String, slotName: String) {
        val bundle       = loadBundle(modelName)
        val updatedNamed = bundle.named.toMutableMap()
        updatedNamed.remove(slotName)
        saveBundle(modelName, bundle.copy(named = updatedNamed))
    }

    /** Clears the default profile. The model reverts to its raw scale/rotation on next placement. */
    fun resetDefault(modelName: String) {
        val bundle = loadBundle(modelName)
        saveBundle(modelName, bundle.copy(default = null))
    }

    /**
     * True if any profile data (default or named) exists for this model.
     * Used by LibraryActivity to show the "✓ Saved" badge.
     */
    fun hasAnyProfile(modelName: String): Boolean {
        val bundle = loadBundle(modelName)
        return bundle.default != null || bundle.named.isNotEmpty()
    }

    fun getLastSavedTime(modelName: String): Long {
        val file = fileFor(modelName)
        return if (file.exists()) file.lastModified() else 0L
    }
}