package com.example.pracazaliczeniowa.converter

import com.example.pracazaliczeniowa.Helpers.ConverterBridge
import com.example.pracazaliczeniowa.Helpers.ConverterRegistry


object GlbConverter : ConverterBridge {

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("assimp")       // ← load dependency FIRST
            System.loadLibrary("glbconverter") // ← then load your library
            nativeLoaded = true
            ConverterRegistry.instance = this
        } catch (e: UnsatisfiedLinkError) {
            // log which library actually failed
            android.util.Log.e("GlbConverter", "Failed to load native library: ${e.message}")
        }
    }

    override fun convertToGlb(inputPath: String, outputPath: String): Boolean {
        check(nativeLoaded) { "Native converter not loaded" }
        return nativeConvert(inputPath, outputPath)
    }


    private external fun nativeConvert(inputPath: String, outputPath: String): Boolean
}