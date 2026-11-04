/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.nativebridge

import kotlin.math.floor
import kotlin.math.sqrt

/** A corner on the floor plane: metres along world x and metres along world z. */
data class PlanPoint(val xMeters: Float, val zMeters: Float)

/** A wall run between two corners. */
data class PlanSegment(val start: PlanPoint, val end: PlanPoint) {

    val lengthMeters: Float
        get() {
            val dx = end.xMeters - start.xMeters
            val dz = end.zMeters - start.zMeters
            return sqrt(dx * dx + dz * dz)
        }
}

/** One traced outline; [closed] is false when the scan had a doorway in it. */
data class PlanLoop(val points: List<PlanPoint>, val closed: Boolean)

/**
 * The native floorplan as Kotlin sees it.
 *
 * `nativeFloorplan` returns one `float[]`, and the layout is a contract with C++
 * (`kFloorplanPayloadHeader` and `packFloorplanPayload` in `geometry/floorplan.hpp`) that no
 * compiler on either side can check. The consequence of a disagreement is not a crash: every field
 * after the wrong offset reads a *different, plausible* number, so the room on the card is the
 * wrong room and nothing says so. The parser therefore trusts the array only as far as its length
 * proves, and refuses everything else:
 *
 *  * a payload shorter than [HEADER], or shorter than its own headers claim, is refused instead of
 *    being read field by field into zeros;
 *  * a count that is not a whole number, is negative, or exceeds the array is refused - C++ writes
 *    counts as floats, and `2.5` segments is a broken writer, not a rounding artefact;
 *  * a non-finite coordinate, area or perimeter is refused, because a NaN line draws as nothing
 *    and a NaN area prints as "NaN m²";
 *  * loops that claim more points than the outline holds are refused, so the points of one loop
 *    cannot be served to another.
 *
 * A *refused* plan is not an error the operator causes: an empty volume and a doorway both produce
 * a plan with `valid = false`, which parses fine and means "no closed room here".
 */
data class FloorplanPayload(
    val valid: Boolean,
    val closed: Boolean,
    val areaSquareMeters: Float,
    val perimeterMeters: Float,
    val occupiedCells: Int,
    val segments: List<PlanSegment>,
    val loops: List<PlanLoop>,
) {

    companion object {

        /**
         * Floats before the variable-length part of the payload.
         *
         * Must equal `kFloorplanPayloadHeader` in `geometry/floorplan.hpp`;
         * `scripts/verify_sources.py` compares the two source constants, because the failure this
         * guard exists for is silent in both languages.
         */
        const val HEADER = 8

        /** Parses the JNI payload; null when it cannot be trusted. */
        fun parse(payload: FloatArray?): FloorplanPayload? {
            if (payload == null || payload.size < HEADER) return null

            val segmentCount = wholeCount(payload[4], payload.size) ?: return null
            val loopCount = wholeCount(payload[5], payload.size) ?: return null
            val pointCount = wholeCount(payload[6], payload.size) ?: return null
            // The cell count does not index the payload, so it is bounded by sanity rather than by
            // the array's length: 431 occupied cells is a 4 x 3 m room and its payload is 26 floats.
            val occupiedCells = wholeCellCount(payload[7]) ?: return null

            val required = HEADER + segmentCount * 4 + loopCount * 2 + pointCount * 2
            if (payload.size < required) return null

            val area = payload[2]
            val perimeter = payload[3]
            if (!area.isFinite() || !perimeter.isFinite()) return null

            var cursor = HEADER
            val segments = ArrayList<PlanSegment>(segmentCount)
            for (index in 0 until segmentCount) {
                val startX = payload[cursor]
                val startZ = payload[cursor + 1]
                val endX = payload[cursor + 2]
                val endZ = payload[cursor + 3]
                cursor += 4
                if (!startX.isFinite() || !startZ.isFinite() || !endX.isFinite() || !endZ.isFinite()) {
                    return null
                }
                segments += PlanSegment(PlanPoint(startX, startZ), PlanPoint(endX, endZ))
            }

            // Each length is a float in the payload and a whole number in the outline it indexes,
            // so it is validated against the outline's own size rather than truncated.
            val loopLengths = IntArray(loopCount)
            for (index in 0 until loopCount) {
                loopLengths[index] = wholeCount(payload[cursor + index], pointCount) ?: return null
            }
            cursor += loopCount
            val loopClosed = BooleanArray(loopCount) { payload[cursor + it] >= 0.5f }
            cursor += loopCount

            val outline = ArrayList<PlanPoint>(pointCount)
            for (index in 0 until pointCount) {
                val x = payload[cursor]
                val z = payload[cursor + 1]
                cursor += 2
                if (!x.isFinite() || !z.isFinite()) return null
                outline += PlanPoint(x, z)
            }

            if (loopLengths.sum() > pointCount) return null

            var offset = 0
            val loops = ArrayList<PlanLoop>(loopCount)
            for (index in 0 until loopCount) {
                val length = loopLengths[index]
                loops += PlanLoop(outline.subList(offset, offset + length), loopClosed[index])
                offset += length
            }

            return FloorplanPayload(
                valid = payload[0] >= 0.5f,
                closed = payload[1] >= 0.5f,
                areaSquareMeters = area,
                perimeterMeters = perimeter,
                occupiedCells = occupiedCells,
                segments = segments,
                loops = loops,
            )
        }

        /** More cells than any building this app is sold for has, times ten: a broken header. */
        private const val MAX_CELLS = 10_000_000f

        /**
         * A count that indexes the payload, or null if it is not one.
         *
         * Bounded by the array length: no legitimate count is larger than the payload that carries
         * it, and the bound keeps a corrupt header from asking for a two-billion-element list.
         */
        private fun wholeCount(value: Float, limit: Int): Int? {
            if (!value.isFinite() || value < 0f) return null
            if (value != floor(value)) return null
            if (value > limit.toFloat()) return null
            return value.toInt()
        }

        /**
         * A count that does **not** index the payload — the occupied cells — so a real room is not
         * refused for carrying more cells than floats.
         */
        private fun wholeCellCount(value: Float): Int? {
            if (!value.isFinite() || value < 0f) return null
            if (value != floor(value)) return null
            if (value > MAX_CELLS) return null
            return value.toInt()
        }
    }
}
