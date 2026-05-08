package com.example.pracazaliczeniowa.Helpers

/**
 * Owns filter and sort state for the library grid.
 * Call [apply] to get the correctly filtered and sorted subset of [allModels].
 */
class LibraryFilterManager(
    private val savedProfiles: Set<String>
) {
    enum class Filter { ALL, RECENT, IMPORTED, SAVED }

    var current: Filter = Filter.ALL
        private set

    fun select(filter: Filter) { current = filter }

    fun apply(allModels: List<ModelItem>): List<ModelItem> {
        val filtered = when (current) {
            Filter.ALL      -> allModels
            Filter.RECENT   -> allModels  // all items, sorted below by activity time

            Filter.IMPORTED -> allModels.filter { !it.isAsset }
            Filter.SAVED    -> allModels.filter { it.profileKey in savedProfiles }
        }
        // Sort by the most recent activity: a saved/modified profile beats the
        // raw creation/import time, so prefer lastModified and fall back to createdAt.
        return filtered.sortedByDescending { maxOf(it.lastModified, it.createdAt) }
    }
}