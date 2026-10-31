/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.render

import android.util.Log
import com.constructar.core.model.ConduitPresets
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Material factory for custom geometry, built on SceneView's **typed** `MaterialLoader` API.
 *
 * SceneView 4.63.0 exposes the colour factories directly:
 *
 * ```kotlin
 * materialLoader.createColorInstance(color: Int, metallic, roughness, reflectance): MaterialInstance
 * materialLoader.createUnlitColorInstance(color: Int): MaterialInstance
 * ```
 *
 * ConstructAR calls exactly those. There is no reflective lookup, no `Class.forName`, and no
 * `Method.invoke` anywhere in the render path: the compiler checks every colour the app can
 * show, and a SceneView bump that changes a signature fails the build instead of silently
 * dropping geometry.
 *
 * ## Lit vs unlit
 *
 * Routes are **lit** so a pipe picks up ARCore's ambient light estimate and reads as lying *on*
 * a wall. The TSDF room scan is **unlit** and translucent: it is a diagnostic overlay, not a
 * service, and it must never be mistaken for installed hardware.
 *
 * ## Threading / allocation
 *
 * `MaterialLoader.createColorInstance` is `@MainThread`, so instances are created eagerly for
 * the whole palette (see [palette]) and then cached. Creating a material during a frame would
 * both violate the loader's thread contract and allocate in the hot path; a cache hit is a
 * `ConcurrentHashMap` read and nothing else.
 */
class SceneViewMaterialProvider(
    private val materialLoader: MaterialLoader,
) : ColorMaterialProvider {

    private val lit = ConcurrentHashMap<Int, MaterialInstance>(8)
    private val unlit = ConcurrentHashMap<Int, MaterialInstance>(4)

    init {
        // Pre-warm on the constructing (main) thread: every colour the app can render exists
        // before the first frame, so `create`/`createUnlit` never build a material mid-frame.
        palette.forEach { create(it) }
        createUnlit(SCAN_OVERLAY_ARGB)
    }

    override fun create(argb: Int): MaterialInstance? {
        lit[argb]?.let { return it }
        return try {
            materialLoader.createColorInstance(
                color = argb,
                metallic = METALLIC,
                roughness = ROUGHNESS,
                reflectance = REFLECTANCE,
            ).also { lit[argb] = it }
        } catch (error: Throwable) {
            // A missing material must never take the measurement down: the mesh is simply not
            // drawn and the operator keeps recording nodes.
            Log.w(TAG, "lit material for #${Integer.toHexString(argb)} unavailable", error)
            null
        }
    }

    override fun createUnlit(argb: Int): MaterialInstance? {
        unlit[argb]?.let { return it }
        return try {
            materialLoader.createUnlitColorInstance(color = argb).also { unlit[argb] = it }
        } catch (error: Throwable) {
            Log.w(TAG, "unlit material for #${Integer.toHexString(argb)} unavailable", error)
            null
        }
    }

    companion object {
        private const val TAG = "SceneViewMaterials"

        /** Matte, near-dielectric: gypsum and concrete, not chrome. */
        private const val METALLIC = 0.0f
        private const val ROUGHNESS = 0.6f
        private const val REFLECTANCE = 0.35f

        /**
         * The scan overlay: cool grey at ~40 % alpha, so a routed service stays visible through
         * the reconstructed surface. Unlit, so a dark site does not hide the room.
         */
        const val SCAN_OVERLAY_ARGB: Int = 0x668A8F98

        /** Every colour the app can ask for, so nothing is created during a frame. */
        val palette: List<Int> = ConduitPresets.all.map { it.colorArgb }.distinct()
    }
}

/** A material provider that never returns anything (headless/unit-test builds). */
object NoOpMaterialProvider : ColorMaterialProvider {
    override fun create(argb: Int): MaterialInstance? = null
    override fun createUnlit(argb: Int): MaterialInstance? = null
}
