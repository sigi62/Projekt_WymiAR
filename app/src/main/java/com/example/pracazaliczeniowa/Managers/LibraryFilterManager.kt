package com.example.pracazaliczeniowa.Managers

import com.example.pracazaliczeniowa.Objects.ModelItem

class LibraryFilterManager(
    private val savedProfiles: Set<String>
) {
    enum class Filter { ALL, ALPHABETICAL, RECENT, SIZE, IMPORTED, PROFILE }

    var current: Filter = Filter.RECENT
        private set

    var ascending: Boolean = false
        private set

    fun select(filter: Filter) {
        if (current == filter) {
            when {
                filter.hasDirection -> ascending = !ascending
                else -> {
                    current = Filter.ALL
                    ascending = false
                }
            }
        } else {
            current = filter
            ascending = when (filter) {
                Filter.ALPHABETICAL -> true
                Filter.SIZE        -> false
                else               -> false
            }
        }
    }

    fun apply(allModels: List<ModelItem>): List<ModelItem> {
        val filtered = when (current) {
            Filter.ALL          -> allModels
            Filter.RECENT       -> allModels
            Filter.ALPHABETICAL -> allModels
            Filter.SIZE         -> allModels
            Filter.IMPORTED     -> allModels.filter { !it.isAsset }
            Filter.PROFILE        -> allModels.filter { it.modelId in savedProfiles }
        }

        return when (current) {
            Filter.ALPHABETICAL -> {
                if (ascending) filtered.sortedBy   { it.name.lowercase() }
                else           filtered.sortedByDescending { it.name.lowercase() }
            }
            Filter.RECENT -> {
                if (ascending) filtered.sortedBy           { maxOf(it.lastModified, it.createdAt) }
                else           filtered.sortedByDescending { maxOf(it.lastModified, it.createdAt) }
            }
            Filter.SIZE -> {
                if (ascending) filtered.sortedBy           { it.sizeBytes }
                else           filtered.sortedByDescending { it.sizeBytes }
            }
            else -> filtered.sortedByDescending { maxOf(it.lastModified, it.createdAt) }
        }
    }

    companion object {
        val Filter.hasDirection get() = this == Filter.RECENT || this == Filter.ALPHABETICAL || this == Filter.SIZE
    }
}