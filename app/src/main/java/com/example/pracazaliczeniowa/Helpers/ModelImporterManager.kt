package com.example.pracazaliczeniowa.Helpers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.pracazaliczeniowa.log
import com.example.pracazaliczeniowa.Converter.ConverterRegistry
import java.io.File

/**
 * Handles importing user-picked .glb / .obj / .stl files from external storage
 * into the app's private internal storage at [Context.filesDir]/models/.
 *
 * OBJ and STL files are converted to GLB via the :converter dynamic feature
 * module (accessed through [ConverterRegistry]) before being stored.
 *
 * All public methods that touch the filesystem are safe to call from a
 * background thread (Dispatchers.IO). Never call [importFromUri] on the
 * main thread — native conversion can take 100–500 ms.
 */
object ModelImportManager {

    private const val MODELS_DIR = "models"

    /**
     * Copies or converts the file at [uri] into internal storage.
     *
     * @return The resulting [ModelItem] on success, null on any failure.
     */
    fun importFromUri(context: Context, uri: Uri): ModelItem? {
        val fileName = resolveFileName(context, uri) ?: run {
            log("Import FAILED: could not resolve filename from URI: $uri")
            return null
        }

        val isGlb         = fileName.endsWith(".glb", ignoreCase = true)
        val isConvertible = fileName.endsWith(".obj", ignoreCase = true) ||
                fileName.endsWith(".stl", ignoreCase = true)

        if (!isGlb && !isConvertible) {
            log("Import FAILED: unsupported file type '$fileName'. Use .glb, .obj, or .stl")
            return null
        }

        val modelsDir     = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
        val finalFileName = if (isGlb) fileName else "${fileName.substringBeforeLast('.')}.glb"
        val dest          = File(modelsDir, finalFileName).canonicalFile

        return try {
            val stream = context.contentResolver.openInputStream(uri) ?: run {
                log("Import FAILED: could not open input stream for $uri")
                return null
            }

            if (isGlb) {
                // ── Direct copy ───────────────────────────────────────────────
                stream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                // ── Conversion path ───────────────────────────────────────────
                // 1. Write the source file to cache so Assimp can read it from disk.
                val tempFile = File(context.cacheDir, fileName)
                stream.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }

                // 2. Make sure the :converter dynamic feature has registered itself.
                //    We use reflection so :app has no compile-time dependency on :converter.
                ensureConverterInitialized()

                if (!ConverterRegistry.isAvailable()) {
                    log("Import FAILED: converter module not available (not installed?)")
                    tempFile.delete()
                    return null
                }

                // 3. Run the native conversion.
                log("Converting $fileName → ${dest.name}")
                val success = ConverterRegistry.instance!!.convertToGlb(
                    tempFile.absolutePath,
                    dest.absolutePath
                )
                tempFile.delete()

                if (!success) {
                    log("Conversion FAILED for $fileName")
                    dest.delete() // remove any partial output
                    return null
                }
            }

            // ── Sanity check ──────────────────────────────────────────────────
            if (!dest.exists() || dest.length() == 0L) {
                log("Import FAILED: destination file missing or empty after operation")
                return null
            }

            val displayName = dest.nameWithoutExtension
            log("Import SUCCESS: $displayName (${dest.length()} bytes)")
            ModelItem(
                name         = displayName,
                modelPath    = dest.absolutePath,
                thumbnailRes = null,
                isAsset      = false,
                createdAt    = System.currentTimeMillis()
            )

        } catch (e: Exception) {
            log("Import FAILED with exception: ${e.message}")
            dest.delete()
            null
        }
    }

    /** Returns all previously imported models, sorted alphabetically. */
    fun loadImported(context: Context): List<ModelItem> {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        if (!modelsDir.exists()) return emptyList()

        return modelsDir
            .listFiles { f -> f.extension.equals("glb", ignoreCase = true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.map { file ->
                ModelItem(
                    name         = file.nameWithoutExtension,
                    modelPath    = file.canonicalPath,
                    thumbnailRes = null,
                    isAsset      = false,
                    createdAt    = file.lastModified()
                )
            }
            ?: emptyList()
    }

    /**
     * Permanently deletes the imported .glb file for [item].
     * Does nothing and returns false if [item] is a bundled asset.
     */
    fun deleteImported(context: Context, item: ModelItem): Boolean {
        if (item.isAsset) return false
        val file = File(item.modelPath)
        return !file.exists() || file.delete()
    }

    /**
     * Renames the .glb file on disk for [item] to [newName] (without extension).
     *
     * Rules:
     * - Does nothing and returns null if [item] is a bundled asset.
     * - Sanitises [newName]: trims whitespace, strips path separators.
     * - Returns null if [newName] is blank after sanitising.
     * - Returns null if a file with the new name already exists in the same
     *   directory (to avoid silently overwriting another model).
     * - On success returns a new [ModelItem] reflecting the updated name and path.
     *
     * Safe to call from a background thread (Dispatchers.IO).
     */
    fun renameModel(context: Context, item: ModelItem, newName: String): ModelItem? {
        if (item.isAsset) {
            log("Rename FAILED: cannot rename bundled asset '${item.name}'")
            return null
        }

        val sanitised = newName.trim().replace(Regex("[/\\\\]"), "")
        if (sanitised.isBlank()) {
            log("Rename FAILED: new name is blank after sanitising")
            return null
        }

        val oldFile = File(item.modelPath)
        if (!oldFile.exists()) {
            log("Rename FAILED: source file does not exist at ${item.modelPath}")
            return null
        }

        val newFile = File(oldFile.parentFile, "$sanitised.glb")
        if (newFile.exists()) {
            log("Rename FAILED: a model named '$sanitised' already exists")
            return null
        }

        return if (oldFile.renameTo(newFile)) {
            log("Rename SUCCESS: '${item.name}' → '$sanitised'")
            ModelItem(
                name         = sanitised,
                modelPath    = newFile.canonicalPath,
                thumbnailRes = item.thumbnailRes,
                isAsset      = false,
                createdAt    = item.createdAt
            )
        } else {
            log("Rename FAILED: File.renameTo returned false for '${item.name}'")
            null
        }
    }

    /** Logs and returns true only if the file exists, is readable, non-empty, and is a .glb. */
    fun verifyImport(context: Context, item: ModelItem): Boolean {
        if (item.isAsset) return true
        val file = File(item.modelPath)

        log("=== Import Verification: ${file.name} ===")
        log("Path:     ${file.absolutePath}")
        log("Exists:   ${file.exists()}")
        log("Readable: ${file.canRead()}")
        log("Size:     ${file.length()} bytes")
        log("Is .glb:  ${file.extension.equals("glb", ignoreCase = true)}")
        log("=========================================")

        return file.exists() &&
                file.canRead() &&
                file.length() > 0 &&
                file.extension.equals("glb", ignoreCase = true)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun resolveFileName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col != -1 && cursor.moveToFirst()) {
                val name = cursor.getString(col)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /**
     * Triggers the GlbConverter object's init block via reflection so it
     * registers itself in [ConverterRegistry]. Safe to call multiple times —
     * object init only runs once per process.
     *
     * We use reflection here deliberately: :app must not have a compile-time
     * dependency on :converter (a dynamic feature module).
     */
    private fun ensureConverterInitialized() {
        if (ConverterRegistry.isAvailable()) return // already registered, skip reflection
        try {
            val clazz    = Class.forName("com.example.pracazaliczeniowa.converter.GlbConverter")
            // Accessing INSTANCE triggers the companion/object init block
            clazz.getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null)
            log("Converter initialized via reflection")
        } catch (e: ClassNotFoundException) {
            log("Converter class not found — dynamic feature not installed yet")
        } catch (e: Exception) {
            log("Converter reflection failed: ${e.message}")
        }
    }
}