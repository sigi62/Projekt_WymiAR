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

    var activeSubset: Filter? = null
        private set

    fun selectSort(filter: Filter) {
        require(filter.isSort)
        if (current == filter) {
            ascending = !ascending
        } else {
            current = filter
            ascending = when (filter) {
                Filter.ALPHABETICAL -> true
                else                -> false
            }
        }
    }

    fun selectSubset(filter: Filter) {
        require(filter.isSubset)
        activeSubset = if (activeSubset == filter) null else filter
    }

    fun apply(allModels: List<ModelItem>): List<ModelItem> {
        val filtered = when (activeSubset) {
            Filter.IMPORTED -> allModels.filter { !it.isAsset }
            Filter.PROFILE  -> allModels.filter { it.modelId in savedProfiles }
            else            -> allModels
        }

        return when (current) {
            Filter.ALPHABETICAL ->
                if (ascending) filtered.sortedBy           { it.name.lowercase() }
                else           filtered.sortedByDescending { it.name.lowercase() }
            Filter.RECENT ->
                if (ascending) filtered.sortedBy           { maxOf(it.lastModified, it.createdAt) }
                else           filtered.sortedByDescending { maxOf(it.lastModified, it.createdAt) }
            Filter.SIZE ->
                if (ascending) filtered.sortedBy           { it.sizeBytes }
                else           filtered.sortedByDescending { it.sizeBytes }
            else -> filtered.sortedByDescending { maxOf(it.lastModified, it.createdAt) }
        }
    }
    fun reset() {
        current = Filter.RECENT
        ascending = false
        activeSubset = null
    }

    companion object {
        val Filter.isSort    get() = this == Filter.RECENT || this == Filter.ALPHABETICAL || this == Filter.SIZE
        val Filter.isSubset  get() = this == Filter.IMPORTED || this == Filter.PROFILE
        val Filter.hasDirection get() = isSort
    }
}