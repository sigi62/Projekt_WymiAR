package com.example.pracazaliczeniowa.Objects

/**
 * Plane-detection / placement modes for the wall-magnet button.
 *
 *  HORIZONTAL – show & snap to horizontal planes only (floor / ceiling)
 *  VERTICAL   – show & snap to vertical planes only  (walls)
 *  BOTH       – show & snap to all plane types
 *  OFF        – hide all plane overlays; placement still works on any surface
 */
enum class PlaneMode {
    HORIZONTAL,
    VERTICAL,
    BOTH,
    OFF
}
