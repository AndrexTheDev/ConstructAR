/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.math

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit quaternion (w, x, y, z) — the orientation primitive used for every surface
 * alignment, anchor pose and curve frame in ConstructAR.
 *
 * Storage order matches glTF / Filament accessors (xyzw) when serialised; the
 * constructor keeps `w` first only because that ordering makes `fromAxisAngle` and
 * `toMatrix` read naturally. See [toXyzw].
 */
@Serializable
data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {

    operator fun times(other: Quat) = Quat(
        w = w * other.w - x * other.x - y * other.y - z * other.z,
        x = w * other.x + x * other.w + y * other.z - z * other.y,
        y = w * other.y - x * other.z + y * other.w + z * other.x,
        z = w * other.z + x * other.y - y * other.x + z * other.w,
    )

    val conjugate get() = Quat(w, -x, -y, -z)

    val length: Float get() = sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): Quat {
        val len = length
        return if (len < 1e-8f) IDENTITY else Quat(w / len, x / len, y / len, z / len)
    }

    /** Rotates [v] by this quaternion (assumes the quaternion is unit length). */
    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q × v) + 2(q × (q × v)) with q = (x, y, z)
        val qv = Vec3(x, y, z)
        val t = (qv cross v) * 2f
        return v + t * w + (qv cross t)
    }

    /** Euler angles in degrees, Z-Y-X order — Filament/SceneView node rotations. */
    fun toEulerDegrees(): Vec3 {
        val sinrCosp = 2f * (w * x + y * z)
        val cosrCosp = 1f - 2f * (x * x + y * y)
        val roll = kotlin.math.atan2(sinrCosp, cosrCosp)

        val sinp = 2f * (w * y - z * x)
        val pitch = if (abs(sinp) >= 1f) kotlin.math.copySign(kotlin.math.PI.toFloat() / 2f, sinp) else kotlin.math.asin(sinp)

        val sinyCosp = 2f * (w * z + x * y)
        val cosyCosp = 1f - 2f * (y * y + z * z)
        val yaw = kotlin.math.atan2(sinyCosp, cosyCosp)

        val radToDeg = 180f / kotlin.math.PI.toFloat()
        return Vec3(roll * radToDeg, pitch * radToDeg, yaw * radToDeg)
    }

    /** `[x, y, z, w]` — the layout glTF/Filament use for quaternion rotations. */
    fun toXyzw() = floatArrayOf(x, y, z, w)

    /** Rotation matrix (column-major 3×3) representation of this quaternion. */
    fun toMatrix3(): Mat3 {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        return Mat3(
            m00 = 1f - 2f * (yy + zz), m01 = 2f * (xy - wz), m02 = 2f * (xz + wy),
            m10 = 2f * (xy + wz), m11 = 1f - 2f * (xx + zz), m12 = 2f * (yz - wx),
            m20 = 2f * (xz - wy), m21 = 2f * (yz + wx), m22 = 1f - 2f * (xx + yy),
        )
    }

    /** Interpolates along the shortest arc. Degenerate inputs fall back to [a]. */
    fun slerp(to: Quat, t: Float): Quat {
        var d = w * to.w + x * to.x + y * to.y + z * to.z
        var target = to
        if (d < 0f) {
            d = -d
            target = Quat(-to.w, -to.x, -to.y, -to.z)
        }
        if (d > 0.9995f) {
            return Quat(
                w + (target.w - w) * t,
                x + (target.x - x) * t,
                y + (target.y - y) * t,
                z + (target.z - z) * t,
            ).normalized()
        }
        val theta = kotlin.math.acos(d.coerceIn(-1f, 1f))
        val sinTheta = sin(theta)
        val wa = sin((1f - t) * theta) / sinTheta
        val wb = sin(t * theta) / sinTheta
        return Quat(
            wa * w + wb * target.w,
            wa * x + wb * target.x,
            wa * y + wb * target.y,
            wa * z + wb * target.z,
        ).normalized()
    }

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        /** Right-handed rotation of [angleRadians] around [axis] (axis need not be unit). */
        fun fromAxisAngle(axis: Vec3, angleRadians: Float): Quat {
            val n = axis.normalizedOrZero()
            if (n.isDegenerate()) return IDENTITY
            val half = angleRadians * 0.5f
            val s = sin(half)
            return Quat(cos(half), n.x * s, n.y * s, n.z * s).normalized()
        }

        /**
         * Shortest-arc rotation taking [from] onto [to] (both directions, not normalised
         * required). Used for surface-normal alignment: `rotationFromTo(WORLD_UP, normal)`.
         */
        fun rotationFromTo(from: Vec3, to: Vec3): Quat {
            val a = from.normalizedOrZero()
            val b = to.normalizedOrZero()
            if (a.isDegenerate() || b.isDegenerate()) return IDENTITY
            val d = (a dot b).coerceIn(-1f, 1f)
            if (d > 1f - 1e-6f) return IDENTITY
            if (d < -1f + 1e-6f) {
                // 180°: any axis orthogonal to `a` is a valid rotation axis.
                return fromAxisAngle(a.anyOrthogonal(), kotlin.math.PI.toFloat())
            }
            val axis = a cross b
            return fromAxisAngle(axis, kotlin.math.acos(d))
        }

        /** Builds a quaternion from an orthonormal basis (columns right/up/forward). */
        fun fromBasis(right: Vec3, up: Vec3, forward: Vec3): Quat {
            val trace = right.x + up.y + forward.z
            return if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                Quat(0.25f * s, (up.z - forward.y) / s, (forward.x - right.z) / s, (right.y - up.x) / s)
            } else if (right.x > up.y && right.x > forward.z) {
                val s = sqrt(1f + right.x - up.y - forward.z) * 2f
                Quat((up.z - forward.y) / s, 0.25f * s, (up.x + right.y) / s, (forward.x + right.z) / s)
            } else if (up.y > forward.z) {
                val s = sqrt(1f + up.y - right.x - forward.z) * 2f
                Quat((forward.x - right.z) / s, (up.x + right.y) / s, 0.25f * s, (up.z + forward.y) / s)
            } else {
                val s = sqrt(1f + forward.z - right.x - up.y) * 2f
                Quat((right.y - up.x) / s, (forward.x + right.z) / s, (up.z + forward.y) / s, 0.25f * s)
            }.normalized()
        }
    }
}

/**
 * 3×3 matrix stored row-major by field name (mRC = row R, column C).
 * Only the handful of operations ConstructAR needs are implemented.
 */
data class Mat3(
    val m00: Float, val m01: Float, val m02: Float,
    val m10: Float, val m11: Float, val m12: Float,
    val m20: Float, val m21: Float, val m22: Float,
) {
    fun column(index: Int): Vec3 = when (index) {
        0 -> Vec3(m00, m10, m20)
        1 -> Vec3(m01, m11, m21)
        2 -> Vec3(m02, m12, m22)
        else -> error("Mat3.column($index)")
    }

    operator fun times(v: Vec3) = Vec3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    operator fun times(other: Mat3): Mat3 = Mat3(
        m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
        m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
        m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
        m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
        m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
        m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
        m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
        m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
        m20 * other.m02 + m21 * other.m12 + m22 * other.m22,
    )

    val transposed: Mat3
        get() = Mat3(m00, m10, m20, m01, m11, m21, m02, m12, m22)

    companion object {
        val IDENTITY = Mat3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
}
