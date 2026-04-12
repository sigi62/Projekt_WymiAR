package com.example.pracazaliczeniowa.Helpers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.pracazaliczeniowa.log
import java.io.File

/**
 * Handles importing user-picked .glb files from external storage into the
 * app's private internal storage at [Context.filesDir]/models/.
 *
 * Files stored here:
 *  • survive app restarts and updates
 *  • are invisible to other apps (no permissions needed to read them back)
 *  • are removed when the user clears app data or uninstalls
 *
 * A [ModelItem] created from an imported file has [ModelItem.isAsset] = false
 * and [ModelItem.modelPath] set to the absolute path of the copied file.
 */
object ModelImportManager {

    /** Sub-folder inside filesDir where imported models are stored. */
    private const val MODELS_DIR = "models"

    /**
     * Copies the .glb file pointed to by [uri] into internal storage.
     *
     * @return The resulting [ModelItem] on success, or null if the file
     *         could not be read, is not a .glb, or the copy failed.
     */
    fun importFromUri(context: Context, uri: Uri): ModelItem? {
        val fileName = resolveFileName(context, uri) ?: return null
        if (!fileName.endsWith(".glb", ignoreCase = true)) return null

        val modelsDir = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
        val dest      = File(modelsDir, fileName)

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    val bytesCopied = input.copyTo(output)
                    log("Import successful: $bytesCopied bytes copied to ${dest.absolutePath}")
                }
            } ?: return null

            val displayName = fileName.substringBeforeLast('.')
            ModelItem(
                name         = displayName,
                modelPath    = dest.absolutePath,
                thumbnailRes = null,
                isAsset      = false
            )
        } catch (e: Exception) {
            log("Import FAILED: ${e.message}")
            dest.delete()
            null
        }
    }

    /**
     * Returns all previously imported models as [ModelItem] instances,
     * sorted alphabetically by name.
     */
    fun loadImported(context: Context): List<ModelItem> {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        if (!modelsDir.exists()) return emptyList()

        return modelsDir.listFiles { f -> f.extension.equals("glb", ignoreCase = true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.map { file ->
                ModelItem(
                    name         = file.nameWithoutExtension,
                    modelPath    = file.absolutePath,
                    thumbnailRes = null,
                    isAsset      = false
                )
            }
            ?: emptyList()
    }

    /**
     * Permanently deletes the imported .glb file for [item].
     * Does nothing if [item] is a bundled asset.
     * @return true if the file was deleted (or didn't exist), false on error.
     */
    fun deleteImported(context: Context, item: ModelItem): Boolean {
        if (item.isAsset) return false
        val file = File(item.modelPath)
        return !file.exists() || file.delete()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun resolveFileName(context: Context, uri: Uri): String? {
        // Try the content resolver display name first (most reliable)
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col != -1 && cursor.moveToFirst()) {
                val name = cursor.getString(col)
                if (!name.isNullOrBlank()) return name
            }
        }
        // Fall back to the last path segment
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }
}