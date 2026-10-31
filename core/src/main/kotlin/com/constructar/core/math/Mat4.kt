/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.math

/**
 * Column-major 4×4 matrix, bit-compatible with ARCore (`Pose.toMatrix`), Filament
 * (`Mat4`/`float[16]`) and glTF node matrices.
 *
 * Layout (indices into [elements]):
 * ```
 *   | 0  4  8 12 |
 *   | 1  5  9 13 |
 *   | 2  6 10 14 |
 *   | 3  7 11 15 |
 * ```
 * Translation therefore lives in elements 12, 13, 14.
 */
data class Mat4(val elements: FloatArray) {

    init {
        require(elements.size == 16) { "Mat4 requires 16 elements, got ${elements.size}" }
    }

    // data class equality on a FloatArray would compare identity; provide value equality.
    override fun equals(other: Any?): Boolean =
        this === other || (other is Mat4 && elements.contentEquals(other.elements))

    override fun hashCode(): Int = elements.contentHashCode()

    operator fun get(row: Int, column: Int): Float = elements[column * 4 + row]

    operator fun times(other: Mat4): Mat4 {
        val a = elements
        val b = other.elements
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = sum
            }
        }
        return Mat4(out)
    }

    /** Transforms a position (w = 1), applying the affine part only. */
    fun transformPoint(p: Vec3): Vec3 = Vec3(
        elements[0] * p.x + elements[4] * p.y + elements[8] * p.z + elements[12],
        elements[1] * p.x + elements[5] * p.y + elements[9] * p.z + elements[13],
        elements[2] * p.x + elements[6] * p.y + elements[10] * p.z + elements[14],
    )

    /** Transforms a direction (w = 0) — ignores translation. */
    fun transformDirection(d: Vec3): Vec3 = Vec3(
        elements[0] * d.x + elements[4] * d.y + elements[8] * d.z,
        elements[1] * d.x + elements[5] * d.y + elements[9] * d.z,
        elements[2] * d.x + elements[6] * d.y + elements[10] * d.z,
    )

    /** Translation column. */
    val translation: Vec3 get() = Vec3(elements[12], elements[13], elements[14])

    /**
     * General 4×4 inverse (cofactor expansion). Returns [IDENTITY] for singular input so a
     * broken tracking pose degrades to a sane transform instead of propagating NaNs.
     */
    fun inverse(): Mat4 {
        val m = elements
        val inv = FloatArray(16)

        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
            m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
            m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
            m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
            m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]

        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
            m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
            m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
            m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
            m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]

        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
            m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
            m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
            m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
            m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]

        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
            m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
            m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
            m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
            m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]

        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (det < 1e-12f && det > -1e-12f) return IDENTITY
        val invDet = 1f / det
        for (i in 0 until 16) inv[i] *= invDet
        return Mat4(inv)
    }

    fun copy(): Mat4 = Mat4(elements.copyOf())

    /** Extracts the rotation as a unit quaternion (assumes an orthonormal upper 3×3). */
    fun rotationQuaternion(): Quat {
        val t = elements[0] + elements[5] + elements[10]
        return when {
            t > 0f -> {
                val s = kotlin.math.sqrt(t + 1f) * 2f
                Quat(0.25f * s, (elements[6] - elements[9]) / s, (elements[8] - elements[2]) / s, (elements[1] - elements[4]) / s)
            }
            elements[0] > elements[5] && elements[0] > elements[10] -> {
                val s = kotlin.math.sqrt(1f + elements[0] - elements[5] - elements[10]) * 2f
                Quat((elements[6] - elements[9]) / s, 0.25f * s, (elements[4] + elements[1]) / s, (elements[8] + elements[2]) / s)
            }
            elements[5] > elements[10] -> {
                val s = kotlin.math.sqrt(1f + elements[5] - elements[0] - elements[10]) * 2f
                Quat((elements[8] - elements[2]) / s, (elements[4] + elements[1]) / s, 0.25f * s, (elements[9] + elements[6]) / s)
            }
            else -> {
                val s = kotlin.math.sqrt(1f + elements[10] - elements[0] - elements[5]) * 2f
                Quat((elements[1] - elements[4]) / s, (elements[8] + elements[2]) / s, (elements[9] + elements[6]) / s, 0.25f * s)
            }
        }.normalized()
    }

    companion object {
        val IDENTITY = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        fun translation(t: Vec3) = Mat4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                t.x, t.y, t.z, 1f,
            ),
        )

        fun scale(s: Vec3) = Mat4(
            floatArrayOf(
                s.x, 0f, 0f, 0f,
                0f, s.y, 0f, 0f,
                0f, 0f, s.z, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /** Rigid transform from a unit quaternion and a translation (metres). */
        fun fromRotationTranslation(rotation: Quat, translation: Vec3): Mat4 {
            val m = rotation.toMatrix3()
            return Mat4(
                floatArrayOf(
                    m.m00, m.m10, m.m20, 0f,
                    m.m01, m.m11, m.m21, 0f,
                    m.m02, m.m12, m.m22, 0f,
                    translation.x, translation.y, translation.z, 1f,
                ),
            )
        }

        /** Wraps an ARCore / Filament column-major matrix without copying. */
        fun fromColumnMajor(array: FloatArray): Mat4 {
            require(array.size == 16) { "Expected 16 column-major elements, got ${array.size}" }
            return Mat4(array.copyOf())
        }
    }
}

/** A rigid transform: rotation then translation, metres in world space. */
data class Pose(val rotation: Quat, val translation: Vec3) {
    fun toMatrix(): Mat4 = Mat4.fromRotationTranslation(rotation, translation)

    fun transform(point: Vec3): Vec3 = rotation.rotate(point) + translation
    fun transformDirection(direction: Vec3): Vec3 = rotation.rotate(direction)

    /** Local X/Y/Z axes in world space (column vectors of the rotation). */
    val right: Vec3 get() = rotation.rotate(Vec3.X_AXIS)
    val up: Vec3 get() = rotation.rotate(Vec3.Y_AXIS)
    val forward: Vec3 get() = rotation.rotate(Vec3.Z_AXIS)

    companion object {
        val IDENTITY = Pose(Quat.IDENTITY, Vec3.ZERO)

        fun lookingAt(position: Vec3, target: Vec3, upHint: Vec3 = Vec3.Y_AXIS): Pose {
            val forward = (target - position).normalizedOrZero()
            if (forward.isDegenerate()) return Pose(Quat.IDENTITY, position)
            val right = (forward cross upHint).normalizedOrZero()
                .let { if (it.isDegenerate()) forward.anyOrthogonal() else it }
            val up = right cross forward
            // Camera-space forward is -Z (OpenGL / ARCore convention).
            return Pose(Quat.fromBasis(right, up, -forward), position)
        }
    }
}
