package com.example.pracazaliczeniowa.Helpers

import android.content.Context
import android.net.Uri
import com.example.pracazaliczeniowa.log
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Sits in front of [ModelImportManager] and handles OBJ / STL → GLB conversion
 * via the on-demand `:converter` dynamic feature module.
 *
 * Typical call-site (e.g. in your LibraryActivity or import button handler):
 *
 *   ConversionManager.importFile(context, uri) { result ->
 *       when (result) {
 *           is ImportResult.Success -> // use result.item
 *           is ImportResult.NeedsModule -> // show "Download converter?" dialog
 *           is ImportResult.Failure -> // show error
 *       }
 *   }
 */
object ConversionManager {

    private const val MODULE_NAME = "converter"
    private val CONVERTIBLE_EXTENSIONS = setOf("obj", "stl")

    sealed class ImportResult {
        data class Success(val item: ModelItem) : ImportResult()
        /** Module not installed — call [requestModuleInstall] then retry. */
        object NeedsModule : ImportResult()
        data class Failure(val reason: String) : ImportResult()
    }

    /**
     * Main entry point. Call from a coroutine (suspend).
     *
     * For .glb files: delegates directly to [ModelImportManager].
     * For .obj/.stl:  converts to .glb first (requires converter module).
     */
    suspend fun importFile(context: Context, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val fileName = resolveExtension(context, uri)
                ?: return@withContext ImportResult.Failure("Cannot resolve filename")

            val ext = fileName.substringAfterLast('.').lowercase()

            return@withContext when {
                ext == "glb" -> {
                    val item = ModelImportManager.importFromUri(context, uri)
                    if (item != null) ImportResult.Success(item)
                    else ImportResult.Failure("GLB import failed")
                }

                ext in CONVERTIBLE_EXTENSIONS -> {
                    if (!isConverterInstalled(context)) {
                        return@withContext ImportResult.NeedsModule
                    }
                    convertAndImport(context, uri, fileName)
                }

                else -> ImportResult.Failure("Unsupported format: .$ext")
            }
        }

    /**
     * Triggers Play Feature Delivery download of the converter module.
     * [onComplete] is called on the main thread with success/failure.
     * If already installed, calls [onComplete](true) immediately.
     */
    fun requestModuleInstall(
        context: Context,
        onComplete: (success: Boolean) -> Unit
    ) {
        val splitInstallManager = SplitInstallManagerFactory.create(context)

        if (isConverterInstalled(context)) {
            onComplete(true)
            return
        }

        val request = SplitInstallRequest.newBuilder()
            .addModule(MODULE_NAME)
            .build()

        val listener = object : SplitInstallStateUpdatedListener {
            override fun onStateUpdate(state: com.google.android.play.core.splitinstall.SplitInstallSessionState) {
                when (state.status()) {
                    SplitInstallSessionStatus.INSTALLED -> {
                        log("Converter module installed successfully")
                        splitInstallManager.unregisterListener(this)
                        onComplete(true)
                    }
                    SplitInstallSessionStatus.FAILED -> {
                        log("Converter module install FAILED: ${state.errorCode()}")
                        splitInstallManager.unregisterListener(this)
                        onComplete(false)
                    }
                    else -> log("Converter module status: ${state.status()}")
                }
            }
        }

        splitInstallManager.registerListener(listener)
        splitInstallManager.startInstall(request)
            .addOnFailureListener {
                splitInstallManager.unregisterListener(listener)
                onComplete(false)
            }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun isConverterInstalled(context: Context): Boolean {
        val mgr = SplitInstallManagerFactory.create(context)
        return MODULE_NAME in mgr.installedModules
    }

    private suspend fun convertAndImport(
        context: Context,
        uri: Uri,
        originalFileName: String
    ): ImportResult = withContext(Dispatchers.IO) {
        // Step 1 — copy the source file to a temp location so Assimp can read it
        val tempDir  = File(context.cacheDir, "conversion_tmp").also { it.mkdirs() }
        val tempSrc  = File(tempDir, originalFileName)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempSrc.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult.Failure("Cannot open input stream")
        } catch (e: Exception) {
            return@withContext ImportResult.Failure("Failed to stage source: ${e.message}")
        }

        // Step 2 — convert to .glb
        val glbName  = originalFileName.substringBeforeLast('.') + ".glb"
        val tempGlb  = File(tempDir, glbName)

        // Late-load the converter class so the app module doesn't hard-reference it
        val converted = try {
            val converterClass = Class.forName(
                "com.example.pracazaliczeniowa.converter.GlbConverter"
            )
            val instance = converterClass.getField("INSTANCE").get(null)
            val method   = converterClass.getMethod("convertToGlb", String::class.java, String::class.java)
            method.invoke(instance, tempSrc.absolutePath, tempGlb.absolutePath) as Boolean
        } catch (e: Exception) {
            log("Converter reflection failed: ${e.message}")
            false
        }

        tempSrc.delete() // clean up staged source

        if (!converted || !tempGlb.exists() || tempGlb.length() == 0L) {
            tempGlb.delete()
            return@withContext ImportResult.Failure("Conversion to GLB failed")
        }

        // Step 3 — move converted .glb into permanent models dir via ModelImportManager
        val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
        val dest = File(modelsDir, glbName)
        tempGlb.copyTo(dest, overwrite = true)
        tempGlb.delete()

        val displayName = glbName.substringBeforeLast('.')
        val item = ModelItem(
            name        = displayName,
            modelPath   = dest.absolutePath,
            thumbnailRes = null,
            isAsset     = false
        )

        ImportResult.Success(item)
    }

    private fun resolveExtension(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (col != -1 && cursor.moveToFirst()) {
                val name = cursor.getString(col)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}