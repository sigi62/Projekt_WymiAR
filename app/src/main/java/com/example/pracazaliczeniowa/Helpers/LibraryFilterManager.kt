package com.example.pracazaliczeniowa.Helpers

/**
 * Owns filter and sort state for the library grid.
 * Call [apply] to get the correctly filtered and sorted subset of [allModels].
 *
 * [RECENT] and [ALPHABETICAL] support direction toggling via [toggleDirection].
 * All other filters always sort by most-recent activity (descending).
 */
class LibraryFilterManager(
    private val savedProfiles: Set<String>
) {
    enum class Filter { ALL, ALPHABETICAL, RECENT, IMPORTED, SAVED }

    var current: Filter = Filter.RECENT
        private set

    /** True = descending for RECENT (newest first), ascending for ALPHABETICAL (A→Z). */
    var ascending: Boolean = false   // default: RECENT descending = newest first
        private set

    /**
     * Selects [filter]. If it is already active, flips [ascending] instead
     * (so tapping the same chip a second time reverses the direction).
     */
    fun select(filter: Filter) {
        if (current == filter && filter.hasDirection) {
            ascending = !ascending
        } else {
            current = filter
            // Sensible defaults per filter
            ascending = when (filter) {
                Filter.ALPHABETICAL -> true   // A → Z
                else                -> false  // newest first
            }
        }
    }

    fun apply(allModels: List<ModelItem>): List<ModelItem> {
        val filtered = when (current) {
            Filter.ALL          -> allModels
            Filter.RECENT       -> allModels
            Filter.ALPHABETICAL -> allModels
            Filter.IMPORTED     -> allModels.filter { !it.isAsset }
            Filter.SAVED        -> allModels.filter { it.profileKey in savedProfiles }
        }

        return when (current) {
            Filter.ALPHABETICAL -> {
                if (ascending) filtered.sortedBy   { it.name.lowercase() }
                else           filtered.sortedByDescending { it.name.lowercase() }
            }
            Filter.RECENT -> {
                // ascending = oldest first, descending (default) = newest first
                if (ascending) filtered.sortedBy           { maxOf(it.lastModified, it.createdAt) }
                else           filtered.sortedByDescending { maxOf(it.lastModified, it.createdAt) }
            }
            // ALL / IMPORTED / SAVED: always newest-first
            else -> filtered.sortedByDescending { maxOf(it.lastModified, it.createdAt) }
        }
    }

    companion object {
        /** Only these filters expose a direction toggle to the UI. */
        val Filter.hasDirection get() = this == Filter.RECENT || this == Filter.ALPHABETICAL
    }
}