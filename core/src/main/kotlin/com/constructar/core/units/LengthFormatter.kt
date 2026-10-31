/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.units

import java.util.Locale

/** Metric only: the crews this tool serves measure in metres, centimetres and millimetres. */
enum class LengthUnit(val symbol: String, val metersPerUnit: Float, val defaultDecimals: Int) {
    METER("m", 1f, 2),
    CENTIMETER("cm", 0.01f, 1),
    MILLIMETER("mm", 0.001f, 0),
    ;

    companion object {
        /** Parses a persisted preference, defaulting to metres. */
        fun fromNameOrDefault(name: String?): LengthUnit =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: METER
    }
}

/**
 * Formats measured lengths for the HUD, the PDF protocol and the CSV.
 *
 * `format(14.8234f)` → `"14.82 m"`. Values are rounded (never truncated) so a 0.995 m run
 * reports 1.00 m rather than 0.99 m — an inspector comparing the protocol with a tape
 * measure expects that.
 */
object LengthFormatter {

    fun format(meters: Float, unit: LengthUnit = LengthUnit.METER, decimals: Int = unit.defaultDecimals): String {
        if (!meters.isFinite()) return "—"
        val value = meters / unit.metersPerUnit
        val pattern = "%." + decimals.coerceIn(0, 3) + "f"
        return String.format(Locale.ROOT, pattern, value) + " " + unit.symbol
    }

    /** `"Length: 14.82 m"` — the exact string used on the floating 3D HUD label. */
    fun formatLabel(meters: Float, unit: LengthUnit = LengthUnit.METER, prefix: String = "Length"): String =
        "$prefix: " + format(meters, unit)

    /** Both units, for the protocol header: `"14.82 m (1482 cm)"`. */
    fun formatDual(meters: Float): String =
        format(meters, LengthUnit.METER) + " (" + format(meters, LengthUnit.CENTIMETER, 0) + ")"

    fun formatArea(squareMeters: Float): String = String.format(Locale.ROOT, "%.2f m²", squareMeters)

    fun formatVolume(cubicMeters: Float): String = String.format(Locale.ROOT, "%.2f m³", cubicMeters)

    fun formatDiameter(millimeters: Float): String =
        if (millimeters <= 0f) "—" else "Ø " + String.format(Locale.ROOT, "%.0f mm", millimeters)

    fun formatSection(widthMillimeters: Float, heightMillimeters: Float): String =
        String.format(Locale.ROOT, "%.0f × %.0f mm", widthMillimeters, heightMillimeters)

    /** Deliberate, documented waste factor formatting: `"+7 %"`. */
    fun formatWasteFactor(factor: Float): String =
        "+" + String.format(Locale.ROOT, "%.0f %%", factor * 100f)

    /** Timestamp used across exports; ISO-like and sortable, in the device time zone. */
    fun formatTimestamp(epochMillis: Long, timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        format.timeZone = timeZone
        return format.format(java.util.Date(epochMillis))
    }
}
