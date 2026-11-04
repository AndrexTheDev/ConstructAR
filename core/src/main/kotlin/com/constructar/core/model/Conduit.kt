/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import com.constructar.core.math.Vec3
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

/**
 * The four services a construction crew routes on a site. The kind decides which
 * procedural mesh generator runs (round sweep vs. rectangular channel) and which
 * line of the bill of materials the run feeds.
 */
enum class ConduitKind {
    /** Round cable bundle (NYM, H07V-K, Cat.6, …). Rendered as a circular tube. */
    CABLE,

    /** Round pipe (water, gas, empty conduit / "Leerrohr"). Rendered as a circular tube. */
    PIPE,

    /** Rectangular duct or cable channel. Rendered as a swept rectangular box. */
    DUCT,
    ;

    val isRound: Boolean get() = this == CABLE || this == PIPE
}

/**
 * Immutable description of one conduit type. Everything is metric; millimetres are used
 * for the trade sizes because that is what the packaging, the drill bits and the
 * electrician's invoice use. Conversion happens once, at mesh generation time.
 */
@Serializable
data class ConduitSpec(
    val kind: ConduitKind,
    val label: String,
    /** Outer diameter in millimetres — round profiles only. */
    val diameterMm: Float = 0f,
    /** Outer width in millimetres — rectangular profiles only (horizontal extent). */
    val widthMm: Float = 0f,
    /** Outer height in millimetres — rectangular profiles only (extent along the surface normal). */
    val heightMm: Float = 0f,
    /** ARGB colour used for the in-scene mesh and the legend. */
    val colorArgb: Int = DEFAULT_COLOR,
    /** Procureable material description, e.g. `"LSZH 3 × 2.5 mm²"` or `"PVC-Kanal 60 × 40"`. */
    val material: String = "",
    /** Mounting clearance in millimetres between the surface and the conduit's back face. */
    val standoffMm: Float = 0f,
) {
    init {
        require(kind.isRound || (widthMm > 0f && heightMm > 0f)) {
            "Rectangular conduit requires both widthMm and heightMm (got ${widthMm}x$heightMm)"
        }
        require(widthMm >= 0f && heightMm >= 0f && diameterMm >= 0f) {
            "Conduit dimensions must be non-negative"
        }
        require(standoffMm >= 0f) { "standoffMm must be non-negative" }
    }

    /** Outer diameter in metres for round profiles (0 for ducts). */
    val outerDiameterMeters: Float get() = diameterMm / 1000f

    val widthMeters: Float get() = widthMm / 1000f
    val heightMeters: Float get() = heightMm / 1000f
    val standoffMeters: Float get() = standoffMm / 1000f

    /**
     * Distance from the mounting surface to the conduit's centre axis, in metres.
     * Round: radius + standoff. Rectangular: half the height + standoff (the channel
     * lies flat on the wall with its back face on the surface).
     */
    val axisOffsetFromSurface: Float
        get() = when (kind) {
            ConduitKind.CABLE, ConduitKind.PIPE -> outerDiameterMeters * 0.5f + standoffMeters
            ConduitKind.DUCT -> heightMeters * 0.5f + standoffMeters
        }

    /** `"Ø 16 mm"`, `"60 × 40 mm"` — the trade designation shown in every export. */
    val sizeLabel: String
        get() = when (kind) {
            ConduitKind.CABLE, ConduitKind.PIPE -> "Ø ${diameterMm.fmt()} mm"
            ConduitKind.DUCT -> "${widthMm.fmt()} × ${heightMm.fmt()} mm"
        }

    /** Stable key used to aggregate the bill of materials. */
    val bomKey: String get() = "${kind.name}|${material}|$sizeLabel|${standoffMm.fmt()}"

    /**
     * `16.5` for a 16,5 mm cable - a dot, on every phone.
     *
     * `"%.1f".format(this)` uses the *device* locale, and this string is not a caption: it is part
     * of the persisted run label, of the bill-of-materials key and of the CSV. A German phone wrote
     * `Ø 16,5 mm`, so the same conduit bought on two phones became two order lines, each rounded up
     * (finding 46). `Locale.ROOT` is the same rule the lengths, the CSV and the protocol follow.
     */
    private fun Float.fmt(): String =
        if (abs(this - Math.round(this).toFloat()) < 0.01f) {
            Math.round(this).toString()
        } else {
            String.format(Locale.ROOT, "%.1f", this)
        }

    companion object {
        /** Site-signal orange — readable against concrete, plaster and brick. */
        const val DEFAULT_COLOR: Int = 0xFFFF6B00.toInt()

        fun cable(diameterMm: Float, label: String = "Cable Ø${diameterMm.toInt()}") = ConduitSpec(
            kind = ConduitKind.CABLE,
            label = label,
            diameterMm = diameterMm,
            colorArgb = 0xFFFF8A00.toInt(),
            material = "LSZH cable Ø${diameterMm.toInt()} mm",
        )

        fun pipe(diameterMm: Float, label: String = "Pipe Ø${diameterMm.toInt()}") = ConduitSpec(
            kind = ConduitKind.PIPE,
            label = label,
            diameterMm = diameterMm,
            colorArgb = 0xFF2E9BFF.toInt(),
            material = "Composite pipe Ø${diameterMm.toInt()} mm",
        )

        fun duct(widthMm: Float, heightMm: Float, label: String = "Duct ${widthMm.toInt()}×${heightMm.toInt()}") =
            ConduitSpec(
                kind = ConduitKind.DUCT,
                label = label,
                widthMm = widthMm,
                heightMm = heightMm,
                colorArgb = 0xFF15C39A.toInt(),
                material = "PVC channel ${widthMm.toInt()} × ${heightMm.toInt()} mm",
            )
    }
}

/**
 * The preset palette shown on the AR tool bar. Kept tiny on purpose: a worker wearing
 * gloves gets four big choices, not a dropdown of forty.
 */
object ConduitPresets {
    val all: List<ConduitSpec> = listOf(
        ConduitSpec.cable(10f, "Cable Ø10"),
        ConduitSpec.cable(16f, "Cable Ø16"),
        ConduitSpec.pipe(20f, "Pipe Ø20"),
        ConduitSpec.pipe(32f, "Pipe Ø32"),
        ConduitSpec.duct(60f, 40f, "Channel 60×40"),
        ConduitSpec.duct(100f, 60f, "Duct 100×60"),
    )

    fun byLabel(label: String): ConduitSpec? = all.firstOrNull { it.label == label }
}

/**
 * Which surface a node sits on. Derived from the surface normal and shown to the user
 * ("wall run", "ceiling run") because the mounting hardware differs per surface.
 */
enum class SurfaceKind {
    FLOOR, WALL, CEILING, UNKNOWN;

    companion object {
        /**
         * Classifies a world-space surface normal. ARCore's plane normals point away from
         * the *surface material*, so an upward normal belongs to a floor and a downward
         * normal to a ceiling — after [facingCamera] has been applied the normal may be
         * flipped; callers therefore pass the *geometric* (unflipped) normal here.
         */
        fun fromNormal(normal: Vec3): SurfaceKind {
            val n = normal.normalizedOrZero()
            if (n.isDegenerate()) return UNKNOWN
            return when {
                abs(n.y) < 0.6f -> WALL
                n.y > 0f -> FLOOR
                else -> CEILING
            }
        }
    }
}
