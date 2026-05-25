package com.example.pracazaliczeniowa.Managers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.pracazaliczeniowa.Activities.log
import com.example.pracazaliczeniowa.Helpers.ConverterRegistry
import com.example.pracazaliczeniowa.Objects.ModelFileUtils
import com.example.pracazaliczeniowa.Objects.ModelItem
import java.io.File
import java.util.UUID

object ModelImportManager {

    private const val MODELS_DIR = "models"

    private val GLB_EXTENSIONS = setOf("glb")

    private val CONVERTIBLE_EXTENSIONS = setOf(
        "obj",
        "stl",
        "fbx",
        "dae",
        "gltf",
        "3ds",
        "ply"
    )

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
                stream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                val tempFile = File(context.cacheDir, fileName)
                stream.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }

                if (ext == "obj") {
                    copyMtlSidecar(context, uri, fileName)
                }

                ensureConverterInitialized()

                if (!ConverterRegistry.isAvailable()) {
                    log("Import FAILED: converter module not available (not installed?)")
                    tempFile.delete()
                    return null
                }

                log("Converting $fileName → ${dest.name}")
                val success = ConverterRegistry.instance!!.convertToGlb(
                    tempFile.absolutePath,
                    dest.absolutePath
                )

                tempFile.delete()
                if (ext == "obj") cleanMtlSidecar(context, fileName)

                if (!success) {
                    log("Conversion FAILED for $fileName")
                    dest.delete()
                    return null
                }
            }

            if (!dest.exists() || dest.length() == 0L) {
                log("Import FAILED: destination file missing or empty after operation")
                return null
            }

            val format = if (isGlb) null else ext
            if (format != null) {
                File(dest.parentFile, "${dest.nameWithoutExtension}.meta")
                    .writeText(format)
            }

            val displayName = dest.nameWithoutExtension
            val bounds = ModelFileUtils.readBounds(dest)
            log("Import SUCCESS: $displayName (${dest.length()} bytes), bounds=$bounds")
            ModelItem(
                modelId = UUID.randomUUID().toString(),
                name = displayName,
                modelPath = dest.absolutePath,
                thumbnailRes = null,
                isAsset = false,
                createdAt = System.currentTimeMillis(),
                defaultSizeM = bounds,
                sizeBytes = dest.length(),
                sourceFormat = format
            )

        } catch (e: Exception) {
            log("Import FAILED with exception: ${e.message}")
            dest.delete()
            null
        }
    }

    fun loadImported(context: Context): List<ModelItem> {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        if (!modelsDir.exists()) return emptyList()

        return modelsDir
            .listFiles { f -> f.extension.equals("glb", ignoreCase = true) }
            ?.sortedBy { it.nameWithoutExtension }
            ?.map { file ->
                val metaFile = File(file.parentFile, "${file.nameWithoutExtension}.meta")
                val sourceFormat = if (metaFile.exists()) metaFile.readText().trim().lowercase() else null
                ModelItem(
                    modelId    = UUID.randomUUID().toString(),
                    name         = file.nameWithoutExtension,
                    modelPath    = file.canonicalPath,
                    thumbnailRes = null,
                    isAsset      = false,
                    createdAt    = file.lastModified(),
                    defaultSizeM = ModelFileUtils.readBounds(file),
                    sizeBytes    = file.length(),
                    sourceFormat = sourceFormat
                )
            }
            ?: emptyList()
    }

    fun deleteImported(context: Context, item: ModelItem): Boolean {
        if (item.isAsset) return false
        val file = File(item.modelPath)
        File(file.parentFile, "${file.nameWithoutExtension}.meta").delete()
        return !file.exists() || file.delete()
    }

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
        val oldMeta = File(oldFile.parentFile, "${oldFile.nameWithoutExtension}.meta")
        val newMeta = File(newFile.parentFile, "${newFile.nameWithoutExtension}.meta")
        if (oldMeta.exists()) oldMeta.renameTo(newMeta)

        val profileDir = File(context.filesDir, "profiles")
        val oldProfile = File(profileDir, "${oldFile.nameWithoutExtension}.json")
        val newProfile = File(profileDir, "$sanitised.json")
        if (oldProfile.exists()) oldProfile.renameTo(newProfile)

        return if (oldFile.renameTo(newFile)) {
            log("Rename SUCCESS: '${item.name}' → '$sanitised'")
            ModelItem(
                modelId = item.modelId,
                name = sanitised,
                modelPath = newFile.canonicalPath,
                thumbnailRes = item.thumbnailRes,
                isAsset = false,
                createdAt = item.createdAt,
                sizeBytes = newFile.length(),
                sourceFormat = item.sourceFormat,
            )
        } else {
            log("Rename FAILED: File.renameTo returned false for '${item.name}'")
            null
        }
    }

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

    private fun copyMtlSidecar(context: Context, objUri: Uri, objFileName: String) {
        val mtlFileName = "${objFileName.substringBeforeLast('.')}.mtl"
        val tempMtl     = File(context.cacheDir, mtlFileName)

        val mtlUriString = objUri.toString()
            .substringBeforeLast('/') + "/$mtlFileName"

        try {
            context.contentResolver.openInputStream(Uri.parse(mtlUriString))?.use { input ->
                tempMtl.outputStream().use { output -> input.copyTo(output) }
                log("MTL sidecar copied: $mtlFileName")
                return
            }
        } catch (_: Exception) {  }

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

    private fun cleanMtlSidecar(context: Context, objFileName: String) {
        val mtlFileName = "${objFileName.substringBeforeLast('.')}.mtl"
        File(context.cacheDir, mtlFileName).delete()
    }


    private fun ensureConverterInitialized() {
        if (ConverterRegistry.isAvailable()) return
        try {
            val clazz = Class.forName("com.example.pracazaliczeniowa.converter.GlbConverter")
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
        val fixedUuid = UUID.nameUUIDFromBytes(assetPath.toByteArray(Charsets.UTF_8)).toString()
        return ModelItem(
            modelId      = fixedUuid,
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
