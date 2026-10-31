/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.math

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Minimal, allocation-conscious 3-vector used across the whole product.
 *
 * Deliberately *not* kotlin-math's `Float3`: :core must stay dependency-free so the
 * measurement engine can be unit-tested on the host JVM and reused by the export
 * tooling. Conversion helpers to kotlin-math / Filament live in :ar-engine.
 *
 * Units are metres throughout (ARCore world space, glTF, PLY, CSV, PDF).
 */
@Serializable
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Float) = Vec3(x * scalar, y * scalar, z * scalar)
    operator fun div(scalar: Float) = Vec3(x / scalar, y / scalar, z / scalar)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun get(index: Int): Float = when (index) {
        0 -> x
        1 -> y
        2 -> z
        else -> error("Vec3 index out of range: $index")
    }

    /** Dot product. */
    infix fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    /** Right-handed cross product (`this × other`). */
    infix fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    val lengthSquared: Float get() = x * x + y * y + z * z
    val length: Float get() = sqrt(lengthSquared)

    /** Unit vector, or [FALLBACK_UP] when the vector is degenerate (never NaN). */
    fun normalized(): Vec3 {
        val len = length
        return if (len > EPSILON) Vec3(x / len, y / len, z / len) else FALLBACK_UP
    }

    /** Same as [normalized] but preserves degenerate input instead of substituting. */
    fun normalizedOrZero(): Vec3 {
        val len = length
        return if (len > EPSILON) Vec3(x / len, y / len, z / len) else ZERO
    }

    /** True when the vector is short enough to be numerically meaningless. */
    fun isDegenerate(epsilon: Float = EPSILON): Boolean = lengthSquared < epsilon * epsilon

    fun distanceTo(other: Vec3): Float = (this - other).length
    fun distanceSquaredTo(other: Vec3): Float = (this - other).lengthSquared

    /** Component-wise minimum / maximum — used for AABBs. */
    fun minWith(other: Vec3) = Vec3(min(x, other.x), min(y, other.y), min(z, other.z))
    fun maxWith(other: Vec3) = Vec3(max(x, other.x), max(y, other.y), max(z, other.z))
    fun abs() = Vec3(abs(x), abs(y), abs(z))

    fun lerp(other: Vec3, t: Float) = Vec3(
        x + (other.x - x) * t,
        y + (other.y - y) * t,
        z + (other.z - z) * t,
    )

    /** Angle in radians between two vectors, clamped so rounding cannot produce NaN. */
    fun angleTo(other: Vec3): Float {
        val a = normalizedOrZero()
        val b = other.normalizedOrZero()
        if (a.isDegenerate() || b.isDegenerate()) return 0f
        return acos((a dot b).coerceIn(-1f, 1f))
    }

    /** Any unit vector orthogonal to this one. Stable for near-any input direction. */
    fun anyOrthogonal(): Vec3 {
        val unit = normalizedOrZero()
        if (unit.isDegenerate()) return X_AXIS
        val axis = if (abs(unit.x) < 0.9f) X_AXIS else Y_AXIS
        return (unit cross axis).normalized()
    }

    fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)

    companion object {
        /** Length (metres) below which a vector is treated as degenerate — 1 micron. */
        const val EPSILON: Float = 1e-6f

        val ZERO = Vec3(0f, 0f, 0f)
        val X_AXIS = Vec3(1f, 0f, 0f)
        val Y_AXIS = Vec3(0f, 1f, 0f)
        val Z_AXIS = Vec3(0f, 0f, 1f)
        val FALLBACK_UP = Y_AXIS

        fun of(x: Float, y: Float, z: Float) = Vec3(x, y, z)
        fun of(array: FloatArray, offset: Int = 0) =
            Vec3(array[offset], array[offset + 1], array[offset + 2])
    }
}

/** Average of a point cloud (used for plane fitting and mesh centroids). */
fun Iterable<Vec3>.centroid(): Vec3 {
    var sx = 0.0
    var sy = 0.0
    var sz = 0.0
    var count = 0
    for (v in this) {
        sx += v.x
        sy += v.y
        sz += v.z
        count++
    }
    return if (count == 0) Vec3.ZERO else Vec3((sx / count).toFloat(), (sy / count).toFloat(), (sz / count).toFloat())
}
