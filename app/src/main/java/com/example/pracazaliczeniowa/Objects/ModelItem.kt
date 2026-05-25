package com.example.pracazaliczeniowa.Objects

import java.util.UUID

data class ModelItem(
    val modelId: String,
    val name: String,
    val modelPath: String,
    val thumbnailRes: Int? = null,
    val isAsset: Boolean = true,
    val defaultSizeM: Triple<Float, Float, Float>? = null,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val createdAt: Long = 0L,
    val sourceFormat: String? = null
)