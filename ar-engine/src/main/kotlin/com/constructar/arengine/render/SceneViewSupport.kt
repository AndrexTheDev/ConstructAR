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
    private val unlit = ConcurrentHashMap<Int, MaterialInstance>(6)

    init {
        // Pre-warm on the constructing (main) thread: every colour the app can render exists
        // before the first frame, so `create`/`createUnlit` never build a material mid-frame.
        palette.forEach { create(it) }
        createUnlit(SCAN_OVERLAY_ARGB)
        createUnlit(NODE_MARKER_ARGB)
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
         * The scan overlay: violet at 60 % alpha, so a routed service stays visible through the
         * reconstructed surface. Unlit, so a dark site does not hide the room.
         *
         * It used to be cool grey at 40 %. On the device the report was *"man sieht nicht, was
         * gescannt ist"*, and grey at 40 % over a plastered wall in daylight is very nearly the
         * wall: the one surface that answers "has anything been captured" was the one the operator
         * could not see (finding 267). The hue is now a colour no service in [palette] uses, the
         * legend on the glass names it in words, and the alpha went up rather than down.
         *
         * `ModeStyle.CAPTURE_OVERLAY_ARGB` in `:app` is this same value, asserted equal by
         * `MeasureModeTest`: the legend cannot explain a colour the mesh is not drawn in.
         */
        const val SCAN_OVERLAY_ARGB: Int = 0x99A25BFF.toInt()

        /**
         * The measured points: lime, unlit, fully opaque (finding 269).
         *
         * A node marker answers "where is the point I just set" and has to read against three
         * different backgrounds - a plastered wall, the violet scan overlay and the conduit tube
         * drawn through it - so it is never shaded (a lit sphere in a dark corner disappears into
         * the corner) and it uses the accent rather than the cable's gold, because the marker is
         * about the *measurement* and the tube is about the *installation*.
         *
         * Pre-warmed like the scan overlay: `createUnlit` is called from the render thread when a
         * mesh is uploaded, and the material loader may only build instances on the main thread.
         */
        const val NODE_MARKER_ARGB: Int = 0xFFC8F04A.toInt()

        /** Every colour the app can ask for, so nothing is created during a frame. */
        val palette: List<Int> = ConduitPresets.all.map { it.colorArgb }.distinct()
    }
}

/** A material provider that never returns anything (headless/unit-test builds). */
object NoOpMaterialProvider : ColorMaterialProvider {
    override fun create(argb: Int): MaterialInstance? = null
    override fun createUnlit(argb: Int): MaterialInstance? = null
}
