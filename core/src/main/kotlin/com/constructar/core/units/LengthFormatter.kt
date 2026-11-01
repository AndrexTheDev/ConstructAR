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
 * reports 1.00 m rather than 0.99 m — an inspector comparing the protocol with a tape measure
 * expects that.
 *
 * "Rounded" means `String.format`, i.e. half-up on the binary value that is actually stored,
 * which is why the boundary is tested from both sides: `0.994f` prints `0.99 m` and `0.995f`
 * prints `1.00 m`. The difference is half a millimetre, three orders of magnitude below the
 * accuracy this app claims.
 */
object LengthFormatter {

    fun format(meters: Float, unit: LengthUnit = LengthUnit.METER, decimals: Int = unit.defaultDecimals): String =
        number(meters, unit, decimals) + " " + unit.symbol

    /**
     * `number(14.8234f)` → `"14.82"` — the digits alone, `Locale.ROOT`, for a resource that
     * carries the unit itself (`hud_length` is `%1$s m`, so the spacing stays translatable).
     *
     * This exists because the alternative was formatting the HUD through a `%1$.2f` resource,
     * which takes its decimal mark from the device: a German phone wrote `12,50 m` for the run
     * and `28.80 m` for the room total three lines below it, on one screen, about one room.
     */
    fun number(meters: Float, unit: LengthUnit = LengthUnit.METER, decimals: Int = unit.defaultDecimals): String {
        if (!meters.isFinite()) return "—"
        val value = meters / unit.metersPerUnit
        val pattern = "%." + decimals.coerceIn(0, 3) + "f"
        return String.format(Locale.ROOT, pattern, value)
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
