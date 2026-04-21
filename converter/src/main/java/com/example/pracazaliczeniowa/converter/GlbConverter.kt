package com.example.pracazaliczeniowa.converter

import java.io.File

/**
 * Kotlin facade for the native Assimp-based converter.
 * Call [isAvailable] first — returns false if the .so hasn't been loaded yet
 * (e.g. dynamic feature module not yet installed).
 */
object GlbConverter {

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("glbconverter")
            nativeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            // Module not installed yet — handled gracefully by caller
        }
    }

    fun isAvailable(): Boolean = nativeLoaded

    /**
     * Converts an .obj or .stl file at [inputPath] to a .glb file at [outputPath].
     * Returns true on success. Thread-safe (Assimp is re-entrant per import).
     *
     * Must be called on a background thread — conversion can take 100–500ms.
     */
    fun convertToGlb(inputPath: String, outputPath: String): Boolean {
        check(nativeLoaded) { "Native converter not loaded" }
        return nativeConvert(inputPath, outputPath)
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    private external fun nativeConvert(inputPath: String, outputPath: String): Boolean
}