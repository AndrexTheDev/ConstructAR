package com.constructar.core.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformMathTest {

    @Test
    fun `matrix inverse round trips a point`() {
        val rotation = Quat.fromAxisAngle(Vec3(0.3f, 1f, 0.2f), 0.7f)
        val pose = Pose(rotation, Vec3(1.5f, -0.5f, 3f))
        val matrix = pose.toMatrix()
        val point = Vec3(0.2f, 0.4f, -1.1f)

        val moved = matrix.transformPoint(point)
        val back = matrix.inverse().transformPoint(moved)
        assertEquals(point.x, back.x, 1e-4f)
        assertEquals(point.y, back.y, 1e-4f)
        assertEquals(point.z, back.z, 1e-4f)
    }

    @Test
    fun `pose transform agrees with matrix transform`() {
        val pose = Pose(Quat.fromAxisAngle(Vec3.Y_AXIS, 1.2f), Vec3(2f, 0f, -1f))
        val point = Vec3(0f, 0f, 1f)
        val viaPose = pose.transform(point)
        val viaMatrix = pose.toMatrix().transformPoint(point)
        assertEquals(viaPose.x, viaMatrix.x, 1e-5f)
        assertEquals(viaPose.y, viaMatrix.y, 1e-5f)
        assertEquals(viaPose.z, viaMatrix.z, 1e-5f)
    }

    @Test
    fun `quaternion rotation preserves length and inverts cleanly`() {
        val q = Quat.fromAxisAngle(Vec3(1f, 1f, 0f), 2.4f)
        val v = Vec3(0.3f, -0.7f, 0.2f)
        val rotated = q.rotate(v)
        assertEquals(v.length, rotated.length, 1e-5f)
        val back = q.conjugate.rotate(rotated)
        assertEquals(v.x, back.x, 1e-5f)
        assertEquals(v.y, back.y, 1e-5f)
    }

    @Test
    fun `quaternion round trips through a matrix`() {
        val q = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), 1.9f).normalized()
        val matrix = Mat4.fromRotationTranslation(q, Vec3.ZERO)
        val recovered = matrix.rotationQuaternion()
        val probe = Vec3(0.4f, 0.1f, -0.6f)
        val a = q.rotate(probe)
        val b = recovered.rotate(probe)
        assertEquals(a.x, b.x, 1e-4f)
        assertEquals(a.y, b.y, 1e-4f)
        assertEquals(a.z, b.z, 1e-4f)
    }

    @Test
    fun `slerp endpoints are exact`() {
        val a = Quat.IDENTITY
        val b = Quat.fromAxisAngle(Vec3.Z_AXIS, 1f)
        val start = a.slerp(b, 0f)
        val end = a.slerp(b, 1f)
        val probe = Vec3(1f, 0f, 0f)
        assertEquals(a.rotate(probe).x, start.rotate(probe).x, 1e-5f)
        assertEquals(b.rotate(probe).y, end.rotate(probe).y, 1e-4f)
    }

    @Test
    fun `aabb union and containment`() {
        val a = Aabb(Vec3(-1f, -1f, -1f), Vec3(1f, 1f, 1f))
        val b = Aabb(Vec3(0f, 0f, 0f), Vec3(2f, 2f, 2f))
        val union = a.union(b)
        assertEquals(2f, union.max.x, 1e-5f)
        assertTrue(union.contains(Vec3(1.5f, 1.5f, 1.5f)))
        assertTrue(!a.contains(Vec3(1.5f, 0f, 0f)))
    }

    @Test
    fun `degenerate vectors never produce NaN`() {
        assertEquals(Vec3.FALLBACK_UP, Vec3.ZERO.normalized())
        assertEquals(Vec3.ZERO, Vec3.ZERO.normalizedOrZero())
        assertTrue(Quat.fromAxisAngle(Vec3.ZERO, 1f) == Quat.IDENTITY)
        assertTrue(Quat.rotationFromTo(Vec3(1f, 2f, 3f), Vec3(2f, 4f, 6f)).rotate(Vec3.Y_AXIS).y.isFinite())
    }
}
