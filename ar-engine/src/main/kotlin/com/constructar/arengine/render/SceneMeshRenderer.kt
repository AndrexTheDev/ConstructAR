/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.render

import android.util.Log
import com.constructar.core.math.Aabb
import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.constructar.core.mesh.MeshData
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import com.google.android.filament.Box
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Uploads [MeshData] into Filament and keeps one entity per mesh key.
 *
 * This class talks **only** to Filament (plus one injected material provider), never to a
 * specific SceneView version. That is deliberate: SceneView's node helpers change between
 * minor releases, whereas `EntityManager` / `RenderableManager` / `TransformManager` are the
 * stable contract underneath every SceneView release. The AR host adds `scene` and the
 * per-frame transforms; this class owns the GPU resources and their lifetime.
 *
 * ## Normals
 *
 * Filament has no `NORMAL` vertex attribute — it wants a **tangent frame** (a quaternion that
 * rotates +Z onto the surface normal). [tangentQuaternion] performs that exact conversion, so
 * the scanned mesh shades with its reconstructed normals instead of screen-space derivatives
 * (which would make 2 cm voxel facets look like noise).
 *
 * ## Allocation discipline
 *
 * Buffers are allocated once per mesh upload (meshes change at ≤ 3.3 Hz, not per frame). Per
 * frame the only work is a `setTransform` per visible node.
 */
class SceneMeshRenderer(
    private val engine: Engine,
    private val scene: Scene,
    private val materials: ColorMaterialProvider,
) : MeshSink {
    private class Entry(
        var entity: Int,
        var transformInstance: Int,
        var vertexBuffer: VertexBuffer?,
        var indexBuffer: IndexBuffer?,
        var materialInstance: MaterialInstance?,
        var triangleCount: Int,
        var visible: Boolean = true,
    )

    private val entries = HashMap<String, Entry>(16)

    /** Number of triangles currently on screen (HUD diagnostic). */
    var renderedTriangles: Int = 0
        private set

    /**
     * Uploads (or replaces) the mesh stored under [key].
     *
     * @param argb colour of the material.
     * @param unlit true for overlay geometry (the room scan), false for lit route geometry.
     * @return true when the mesh was uploaded.
     */
    override fun upload(key: String, mesh: MeshData, argb: Int, unlit: Boolean): Boolean {
        if (mesh.isEmpty) return false
        val bounds = mesh.bounds() ?: return false
        remove(key)

        val vertexCount = mesh.vertexCount
        val positions = directBuffer(vertexCount * 12)
        val tangents = directBuffer(vertexCount * 16)
        for (v in 0 until vertexCount) {
            positions.putFloat(mesh.positions[v * 3])
            positions.putFloat(mesh.positions[v * 3 + 1])
            positions.putFloat(mesh.positions[v * 3 + 2])
            val q = tangentQuaternion(mesh.normalAt(v))
            tangents.putFloat(q[0])
            tangents.putFloat(q[1])
            tangents.putFloat(q[2])
            tangents.putFloat(q[3])
        }
        positions.rewind()
        tangents.rewind()

        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertexCount)
            .bufferCount(2)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, positions)
        vertexBuffer.setBufferAt(engine, 1, tangents)

        val indices = directBuffer(mesh.indices.size * 4)
        val intView = indices.asIntBuffer()
        intView.put(mesh.indices)
        intView.rewind()

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(mesh.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, intView)

        val materialInstance = if (unlit) materials.createUnlit(argb) else materials.create(argb)
        if (materialInstance == null) {
            Log.w(TAG, "no material for #${Integer.toHexString(argb)} — mesh $key not rendered")
            engine.destroyVertexBuffer(vertexBuffer)
            engine.destroyIndexBuffer(indexBuffer)
            return false
        }

        val entity = EntityManager.get().create()
        val renderable = RenderableManager.Builder(1)
            .boundingBox(boxOf(bounds))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
            .material(0, materialInstance)
            // A hand-held AR view is always inside the scene bounds; culling a room-scale
            // mesh by its AABB pops the overlay in and out while panning.
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(7)
            .build(engine, entity)
        scene.addEntity(entity)

        val transformManager = engine.transformManager
        entries[key] = Entry(
            entity = entity,
            transformInstance = transformManager.getInstance(entity),
            vertexBuffer = vertexBuffer,
            indexBuffer = indexBuffer,
            materialInstance = materialInstance,
            triangleCount = mesh.triangleCount,
        )
        recomputeTriangleCount()
        return true
    }

    /** Updates the world transform of [key]. Cheap enough to call every frame. */
    override fun setPose(key: String, position: Vec3, rotation: Quat, scale: Float) {
        val entry = entries[key] ?: return
        val matrix = FloatArray(16)
        val q = rotation.normalized()
        val xx = q.x * q.x; val yy = q.y * q.y; val zz = q.z * q.z
        val xy = q.x * q.y; val xz = q.x * q.z; val yz = q.y * q.z
        val wx = q.w * q.x; val wy = q.w * q.y; val wz = q.w * q.z
        matrix[0] = (1 - 2 * (yy + zz)) * scale
        matrix[1] = 2 * (xy + wz) * scale
        matrix[2] = 2 * (xz - wy) * scale
        matrix[3] = 0f
        matrix[4] = 2 * (xy - wz) * scale
        matrix[5] = (1 - 2 * (xx + zz)) * scale
        matrix[6] = 2 * (yz + wx) * scale
        matrix[7] = 0f
        matrix[8] = 2 * (xz + wy) * scale
        matrix[9] = 2 * (yz - wx) * scale
        matrix[10] = (1 - 2 * (xx + yy)) * scale
        matrix[11] = 0f
        matrix[12] = position.x
        matrix[13] = position.y
        matrix[14] = position.z
        matrix[15] = 1f
        engine.transformManager.setTransform(entry.transformInstance, matrix)
    }

    override fun setVisible(key: String, visible: Boolean) {
        val entry = entries[key] ?: return
        if (entry.visible == visible) return
        entry.visible = visible
        val renderableManager = engine.renderableManager
        renderableManager.setLayerMask(
            renderableManager.getInstance(entry.entity),
            if (visible) 0xFF else 0x00,
        )
        recomputeTriangleCount()
    }

    override fun contains(key: String): Boolean = entries.containsKey(key)

    override fun remove(key: String) {
        val entry = entries.remove(key) ?: return
        scene.removeEntity(entry.entity)
        engine.renderableManager.destroy(entry.entity)
        EntityManager.get().destroy(entry.entity)
        entry.vertexBuffer?.let(engine::destroyVertexBuffer)
        entry.indexBuffer?.let(engine::destroyIndexBuffer)
        // Colour material instances are cheap and shared per colour; the provider owns them.
        recomputeTriangleCount()
    }

    fun clear() {
        entries.keys.toList().forEach(::remove)
    }

    /** Releases everything; call from `ON_DESTROY`/`DisposableEffect`. */
    fun release() {
        clear()
        entries.clear()
    }

    private fun recomputeTriangleCount() {
        var total = 0
        for (entry in entries.values) if (entry.visible) total += entry.triangleCount
        renderedTriangles = total
    }

    companion object {
        private const val TAG = "SceneMeshRenderer"

        private fun directBuffer(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        /**
         * Filament tangent-frame quaternion `(x, y, z, w)` rotating +Z onto [normal].
         *
         * `w = cos(θ/2)`, `xyz = axis · sin(θ/2)`, with the axis being `+Z × n`. Degenerate
         * input (zero-length normal) yields the identity frame, never NaN.
         */
        fun tangentQuaternion(normal: Vec3): FloatArray {
            val n = normal.normalizedOrZero()
            if (n.isDegenerate()) return floatArrayOf(0f, 0f, 0f, 1f)
            val dot = n.z.coerceIn(-1f, 1f)
            if (dot > 0.9999f) return floatArrayOf(0f, 0f, 0f, 1f)
            if (dot < -0.9999f) return floatArrayOf(1f, 0f, 0f, 0f) // 180° about X
            val axis = (Vec3.Z_AXIS cross n)
            val axisLength = sqrt(axis.lengthSquared)
            val half = acos(dot) * 0.5f
            val s = sin(half) / axisLength
            return floatArrayOf(axis.x * s, axis.y * s, axis.z * s, cos(half))
        }

        /**
         * Filament `Box` from an AABB. Built from centre/half-extent with a 1 mm pad so a
         * degenerate (planar) scan mesh still has a non-zero volume — Filament rejects a box
         * with a zero half-extent.
         */
        fun boxOf(aabb: Aabb): Box {
            val center = aabb.center
            val half = aabb.halfExtent
            return Box(center.x, center.y, center.z, half.x + 0.001f, half.y + 0.001f, half.z + 0.001f)
        }
    }
}

/**
 * Supplies a colour [MaterialInstance] for a given ARGB value.
 *
 * Implemented in :ar-engine over SceneView's `MaterialLoader` ([SceneViewMaterialProvider]),
 * and additionally as a fake in unit tests. Returning null means "this colour cannot be
 * rendered" — the renderer then skips the mesh instead of crashing.
 *
 * The two shading models are separate calls because they are separate materials in
 * SceneView's loader: lit for routes (they must catch ARCore's light estimate so a pipe reads
 * as lying *on* a wall), unlit for the scan overlay (a diagnostic wash that must not be
 * confused with a routed service).
 */
interface ColorMaterialProvider {
    /** Physically-based (lit) instance for [argb]. */
    fun create(argb: Int): MaterialInstance?

    /** Flat, light-independent instance for [argb]. Defaults to the lit one. */
    fun createUnlit(argb: Int): MaterialInstance? = create(argb)
}
