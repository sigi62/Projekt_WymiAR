package com.example.pracazaliczeniowa.Objects

/**
 * Represents a 3D model available in the library.
 *
 * @param name         Display name shown on the card (e.g. "Cat")
 * @param modelPath    Either:
 *                       • an asset-relative path  ("models/cat.glb") when [isAsset] = true
 *                       • an absolute file path   ("/data/.../models/cat.glb") when [isAsset] = false
 * @param thumbnailRes Drawable resource id for the preview thumbnail.
 *                     Falls back to a generic placeholder if null.
 * @param isAsset      True for bundled assets shipped with the APK.
 *                     False for models imported by the user into internal storage.
 */
data class ModelItem(
    val name: String,
    val modelPath: String,
    val thumbnailRes: Int? = null,
    val isAsset: Boolean = true,
    val profileKey: String = modelPath.substringAfterLast("/").substringBeforeLast("."),
    val defaultSizeM: Triple<Float, Float, Float>? = null,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val createdAt: Long = 0L,
    val sourceFormat: String? = null
)