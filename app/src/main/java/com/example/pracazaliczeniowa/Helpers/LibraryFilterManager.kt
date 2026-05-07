package com.example.pracazaliczeniowa.Helpers

/**
 * Owns filter and sort state for the library grid.
 * Call [apply] to get the correctly filtered and sorted subset of [allModels].
 */
class LibraryFilterManager(
    private val savedProfiles: Set<String>
) {
    enum class Filter { ALL, OWN, IMPORTED, SAVED }
    enum class SortOrder { NEWEST_FIRST, OLDEST_FIRST }

    var current: Filter = Filter.ALL
        private set

    var sortOrder: SortOrder = SortOrder.NEWEST_FIRST
        private set

    fun select(filter: Filter) { current = filter }

    fun setSort(order: SortOrder) { sortOrder = order }

    /** Flips between NEWEST_FIRST and OLDEST_FIRST. Convenient for a toggle button. */
    fun toggleSort() {
        sortOrder = if (sortOrder == SortOrder.NEWEST_FIRST)
            SortOrder.OLDEST_FIRST else SortOrder.NEWEST_FIRST
    }

    fun apply(allModels: List<ModelItem>): List<ModelItem> {
        val filtered = when (current) {
            Filter.ALL      -> allModels
            Filter.OWN      -> allModels.filter { it.isAsset }
            Filter.IMPORTED -> allModels.filter { !it.isAsset }
            Filter.SAVED    -> allModels.filter { it.profileKey in savedProfiles }
        }
        return when (sortOrder) {
            SortOrder.NEWEST_FIRST -> filtered.sortedByDescending { it.lastModified }
            SortOrder.OLDEST_FIRST -> filtered.sortedBy { it.lastModified }
        }
    }
}