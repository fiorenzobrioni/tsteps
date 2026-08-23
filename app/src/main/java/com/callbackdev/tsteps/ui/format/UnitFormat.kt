package com.callbackdev.tsteps.ui.format

import com.callbackdev.tsteps.data.UnitsSystem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Distance/speed rendering for the chosen units — one place, no drift. */
object UnitFormat {

    fun unitMeters(units: UnitsSystem): Double =
        if (units == UnitsSystem.METRIC) 1_000.0 else 1_609.344

    fun distanceValue(meters: Double, units: UnitsSystem): String =
        "%.1f".format(Locale.ROOT, meters / unitMeters(units))

    fun distanceLabel(units: UnitsSystem): String =
        if (units == UnitsSystem.METRIC) "km" else "mi"

    fun distance(meters: Double, units: UnitsSystem): String =
        "${distanceValue(meters, units)} ${distanceLabel(units)}"

    fun distanceKey(units: UnitsSystem): String =
        if (units == UnitsSystem.METRIC) "distance_km" else "distance_mi"

    fun speedValue(speedKmh: Double, units: UnitsSystem): String =
        "%.1f".format(
            Locale.ROOT,
            if (units == UnitsSystem.METRIC) speedKmh else speedKmh / 1.609344
        )

    fun speedLabel(units: UnitsSystem): String =
        if (units == UnitsSystem.METRIC) "km/h" else "mph"

    fun paceLabel(units: UnitsSystem): String =
        if (units == UnitsSystem.METRIC) "min/km" else "min/mi"

    /**
     * Active time at the scale it is being read: `47 min` for a day, `8 h 35 min`
     * for a week, `58 h` for everything since the first commit. Three- and
     * four-digit minute counts are a number nobody converts in their head, and
     * a bare `8 h` would throw away the half hour a week's total still cares
     * about — so the remainder rides along whenever there is one.
     */
    fun activeSpan(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    private val ClockTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** `09:32` — the wall-clock shape used by session hunks and arrays. */
    fun clockTime(epochMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(ClockTime)

    /** `~09:30` — an auto-detected boundary wears its approximation (Fase 11). */
    fun clockTime(epochMillis: Long, zone: ZoneId, approx: Boolean): String =
        (if (approx) "~" else "") + clockTime(epochMillis, zone)
}
