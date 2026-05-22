package com.example.pracazaliczeniowa.Managers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.pracazaliczeniowa.Activities.log
import com.example.pracazaliczeniowa.Helpers.ConverterRegistry
import com.example.pracazaliczeniowa.Objects.ModelFileUtils
import com.example.pracazaliczeniowa.Objects.ModelItem
import java.io.File

/**
 * Handles importing user-picked 3D model files from external storage into the
 * app's private internal storage at [Context.filesDir]/models/.
 *
 * Supported formats:
 *   • .glb  — copied directly (no conversion)
 *   • .obj  — converted via native Assimp; .mtl sidecar copy attempted
 *   • .stl  — converted via native Assimp
 *   • .fbx  — converted via native Assimp
 *   • .dae  — converted via native Assimp (Collada)
 *   • .gltf — converted via native Assimp (text glTF → binary .glb)
 *   • .3ds  — converted via native Assimp (legacy Autodesk 3DS)
 *   • .ply  — converted via native Assimp (3D scan / photogrammetry)
 *
 * Conversion is performed by the :converter dynamic feature module accessed
 * through [ConverterRegistry].
 *
 * OBJ imports also attempt to copy the companion .mtl file into the same cache
 * directory so Assimp can resolve materials during conversion.  Due to Android's
 * per-URI permission model this only works when the file is exposed via a
 * content:// URI that grants access to sibling files (e.g. a file manager that
 * supports it).  If the .mtl cannot be read the import continues anyway and
 * Assimp falls back to the default PBR material.
 *
 * All public methods that touch the filesystem are safe to call from a
 * background thread (Dispatchers.IO). Never call [importFromUri] on the
 * main thread — native conversion can take 100–500 ms.
 */
object ModelImportManager {

    private const val MODELS_DIR = "models"

    /** Extensions accepted as direct GLB (no conversion needed). */
    private val GLB_EXTENSIONS = setOf("glb")

    /**
     * Extensions that require native conversion before storage.
     * Must stay in sync with detectFormat() in glb_converter.cpp.
     */
    private val CONVERTIBLE_EXTENSIONS = setOf(
        "obj",
        "stl",
        "fbx",
        "dae",
        "gltf",
        "3ds",
        "ply"
    )

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

        val ext           = fileName.substringAfterLast('.', "").lowercase()
        val isGlb         = ext in GLB_EXTENSIONS
        val isConvertible = ext in CONVERTIBLE_EXTENSIONS

        if (!isGlb && !isConvertible) {
            log("Import FAILED: unsupported file type '$fileName'. " +
                    "Supported: .glb, .obj, .stl, .fbx, .dae, .gltf, .3ds, .ply")
            return null
        }

        val modelsDir      = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
        val baseName       = fileName.substringBeforeLast('.')
        val finalFileName  = if (isGlb) fileName else "$baseName.glb"
        val dest           = resolveUniqueFile(modelsDir, finalFileName)

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

                // 2. For OBJ files, also try to copy the companion .mtl sidecar.
                //    Assimp looks for the .mtl in the same directory as the .obj,
                //    so it must land in cacheDir alongside tempFile.
                //
                //    Android's content:// permission model grants access only to
                //    the single URI the user picked, not to sibling files in the
                //    same folder. The attempt below works when the file manager
                //    exposes sibling files under predictable URIs (some do, some
                //    don't). If it fails we log and continue — Assimp will use
                //    the default PBR material injected by the native converter.
                if (ext == "obj") {
                    copyMtlSidecar(context, uri, fileName)
                }

                // 3. Make sure the :converter dynamic feature has registered itself.
                //    We use reflection so :app has no compile-time dependency on :converter.
                ensureConverterInitialized()

                if (!ConverterRegistry.isAvailable()) {
                    log("Import FAILED: converter module not available (not installed?)")
                    tempFile.delete()
                    return null
                }

                // 4. Run the native conversion.
                log("Converting $fileName → ${dest.name}")
                val success = ConverterRegistry.instance!!.convertToGlb(
                    tempFile.absolutePath,
                    dest.absolutePath
                )

                // Clean up temp files regardless of outcome
                tempFile.delete()
                if (ext == "obj") cleanMtlSidecar(context, fileName)

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
            val bounds = ModelFileUtils.readBounds(dest)
            log("Import SUCCESS: $displayName (${dest.length()} bytes), bounds=$bounds")
            ModelItem(
                name = displayName,
                modelPath = dest.absolutePath,
                thumbnailRes = null,
                isAsset = false,
                createdAt = System.currentTimeMillis(),
                defaultSizeM = bounds,
                sizeBytes = dest.length()
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
                    name = file.nameWithoutExtension,
                    modelPath = file.canonicalPath,
                    thumbnailRes = null,
                    isAsset = false,
                    createdAt = file.lastModified(),
                    defaultSizeM = ModelFileUtils.readBounds(file),
                    sizeBytes = file.length()
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
                name = sanitised,
                modelPath = newFile.canonicalPath,
                thumbnailRes = item.thumbnailRes,
                isAsset = false,
                createdAt = item.createdAt,
                sizeBytes = newFile.length()
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
     * Returns a [File] inside [dir] that does not yet exist, appending a
     * numeric suffix when necessary so existing files are never overwritten.
     *
     * Examples:
     *   "chair.glb"        → no conflict      → returns "chair.glb"
     *   "chair.glb" exists → conflict         → returns "chair_1.glb"
     *   "chair_1.glb" also exists             → returns "chair_2.glb"
     */
    private fun resolveUniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.')
        val ext  = fileName.substringAfterLast('.')
        var candidate = File(dir, fileName).canonicalFile
        var counter   = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$counter.$ext").canonicalFile
            counter++
        }
        if (counter > 1) {
            log("Name conflict: '$fileName' already exists — saving as '${candidate.name}'")
        }
        return candidate
    }

    /**
     * Attempts to copy the .mtl sidecar that accompanies an .obj file into
     * [Context.cacheDir] so Assimp finds it next to the temp .obj during conversion.
     *
     * Strategy: build a sibling URI by replacing the .obj filename with .mtl in
     * the original URI string. This works for file managers that expose files
     * under predictable content:// URIs (e.g. Files by Google, most OEM pickers).
     * It silently does nothing when the .mtl is inaccessible — conversion
     * continues and the native layer injects a default PBR material instead.
     */
    private fun copyMtlSidecar(context: Context, objUri: Uri, objFileName: String) {
        val mtlFileName = "${objFileName.substringBeforeLast('.')}.mtl"
        val tempMtl     = File(context.cacheDir, mtlFileName)

        // Attempt 1: replace the last path segment of the URI with the .mtl name.
        // Works for content:// URIs where the filename is the last segment.
        val mtlUriString = objUri.toString()
            .substringBeforeLast('/') + "/$mtlFileName"

        try {
            context.contentResolver.openInputStream(Uri.parse(mtlUriString))?.use { input ->
                tempMtl.outputStream().use { output -> input.copyTo(output) }
                log("MTL sidecar copied: $mtlFileName")
                return
            }
        } catch (_: Exception) { /* URI not accessible — try next strategy */ }

        // Attempt 2: for file:// URIs, check if the .mtl sits next to the .obj on disk.
        if (objUri.scheme == "file") {
            val objFile = File(objUri.path ?: return)
            val siblingMtl = File(objFile.parentFile, mtlFileName)
            if (siblingMtl.exists() && siblingMtl.canRead()) {
                try {
                    siblingMtl.copyTo(tempMtl, overwrite = true)
                    log("MTL sidecar copied from filesystem: $mtlFileName")
                    return
                } catch (e: Exception) {
                    log("MTL sidecar copy from filesystem failed: ${e.message}")
                }
            }
        }

        log("MTL sidecar not found or inaccessible for $objFileName — " +
                "conversion will use default PBR material")
    }

    /**
     * Deletes the temp .mtl file from [Context.cacheDir] after conversion,
     * whether or not it was successfully copied in.
     */
    private fun cleanMtlSidecar(context: Context, objFileName: String) {
        val mtlFileName = "${objFileName.substringBeforeLast('.')}.mtl"
        File(context.cacheDir, mtlFileName).delete()
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
            val clazz = Class.forName("com.example.pracazaliczeniowa.converter.GlbConverter")
            // Accessing INSTANCE triggers the companion/object init block
            clazz.getDeclaredField("INSTANCE").also { it.isAccessible = true }.get(null)
            log("Converter initialized via reflection")
        } catch (e: ClassNotFoundException) {
            log("Converter class not found — dynamic feature not installed yet")
        } catch (e: Exception) {
            log("Converter reflection failed: ${e.message}")
        }
    }

    fun bundledItem(context: Context, assetPath: String): ModelItem {
        val name = assetPath.substringAfterLast("/").substringBeforeLast(".")
        val sizeBytes = try { context.assets.openFd(assetPath).use { it.length } } catch (e: Exception) { 0L }
        val bounds = ModelFileUtils.readBounds(context, assetPath)
        return ModelItem(
            name         = name,
            modelPath    = assetPath,
            thumbnailRes = null,
            isAsset      = true,
            createdAt    = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime,
            defaultSizeM = bounds,
            sizeBytes    = sizeBytes
        )
    }
}