package com.example.pracazaliczeniowa.Objects

enum class DistanceUnit {
    METERS, CENTIMETERS, MILLIMETERS;

    fun convert(meters: Float): Pair<Float, String> = when (this) {
        METERS      -> meters to "m"
        CENTIMETERS -> (meters * 100f) to "cm"
        MILLIMETERS -> (meters * 1000f) to "mm"
    }
}