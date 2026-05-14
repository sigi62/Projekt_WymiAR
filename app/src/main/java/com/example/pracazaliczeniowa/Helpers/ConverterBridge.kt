package com.example.pracazaliczeniowa.Helpers

/**
 * Interface defined in :app so it has no dependency on :converter.
 * The :converter dynamic feature module provides the real implementation.
 */
interface ConverterBridge {
    fun convertToGlb(inputPath: String, outputPath: String): Boolean
}

/**
 * Global registry — :converter registers itself here at runtime after install.
 */
object ConverterRegistry {
    @Volatile
    var instance: ConverterBridge? = null

    fun isAvailable(): Boolean = instance != null
}