package com.example.pracazaliczeniowa.Helpers

/**
 * Represents a 3D model available in the library.
 *
 * @param name         Display name shown on the card (e.g. "Cat")
 * @param modelPath    Asset path used by SceneView (e.g. "models/cat.glb")
 * @param thumbnailRes Drawable resource id for the preview thumbnail.
 *                     Use R.drawable.thumb_cat etc.  Falls back to a
 *                     generic placeholder if null.
 */
data class ModelItem(
    val name: String,
    val modelPath: String,
    val thumbnailRes: Int? = null
) {
    /**
     * The key used by [ProfileManager] – derived from the file name
     * without its extension, matching [DefaultModelNode.getModeleName()].
     */
    val profileKey: String
        get() = modelPath.substringAfterLast('/').substringBeforeLast('.')
}
