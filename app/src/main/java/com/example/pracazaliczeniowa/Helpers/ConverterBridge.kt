package com.example.pracazaliczeniowa.Helpers


interface ConverterBridge {
    fun convertToGlb(inputPath: String, outputPath: String): Boolean
}

object ConverterRegistry {
    @Volatile
    var instance: ConverterBridge? = null

    fun isAvailable(): Boolean = instance != null
}