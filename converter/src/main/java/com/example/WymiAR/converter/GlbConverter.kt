package com.example.WymiAR.converter

import android.util.Log
import com.example.WymiAR.Helpers.ConverterBridge
import com.example.WymiAR.Helpers.ConverterRegistry


object GlbConverter : ConverterBridge {

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("assimp")
            System.loadLibrary("glbconverter")
            nativeLoaded = true
            ConverterRegistry.instance = this
        } catch (e: UnsatisfiedLinkError) {
            Log.e("GlbConverter", "Failed to load native library: ${e.message}")
        }
    }

    override fun convertToGlb(inputPath: String, outputPath: String): Boolean {
        check(nativeLoaded) { "Native converter not loaded" }
        return nativeConvert(inputPath, outputPath)
    }


    private external fun nativeConvert(inputPath: String, outputPath: String): Boolean
}