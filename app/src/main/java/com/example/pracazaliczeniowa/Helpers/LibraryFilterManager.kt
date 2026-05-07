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
            Filter.RECENT   -> {
                // Returns items modified in the last 7 days
                val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                allModels.filter { it.lastModified >= sevenDaysAgo }
            }

            Filter.IMPORTED -> allModels.filter { !it.isAsset }
            Filter.SAVED    -> allModels.filter { it.profileKey in savedProfiles }
        }
        return filtered.sortedByDescending { it.lastModified }
    }
}