/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.mesh

import com.constructar.core.math.Vec3

/**
 * The dots that show where the points are (finding 269).
 *
 * Until this existed the app could hold a dozen measured points in ARCore world space and draw
 * **none** of them. The only geometry on the glass was the conduit tube, and the tube starts at the
 * *second* point: the first tap of every run was an act of faith — a number appeared in the HUD and
 * the wall looked exactly as it had a moment before. On the device that reads as *"nichts passiert"*,
 * and the standard remedy for it was tapping again, which is how a run collects a second point one
 * centimetre from the first.
 *
 * A marker is one small unlit sphere per node, merged into a **single** mesh: one upload per change,
 * not one per point, because the uploader allocates buffers per key and a run can hold thirty nodes.
 *
 * `TubeMeshGenerator.sphere` already existed for exactly this ("in-scene node markers") and had never
 * been called outside its own test.
 *
 * Pure geometry, no `android.*`: the shape of the answer is testable without a camera.
 */
object NodeMarkers {

    /**
     * Marker radius, in metres.
     *
     * The largest conduit in the palette is 32 mm across, so a 50 mm sphere is deliberately *bigger*
     * than the tube that will be drawn through it: at three metres on a phone it is about 25 px,
     * which is the smallest dot that still reads on a plastered wall in daylight.
     */
    const val RADIUS_METERS: Float = 0.025f

    /**
     * Radius of the dot on the **current** node - the one the next segment starts from and the one
     * undo takes away (finding 270).
     *
     * All points were the same size, so a run of six dots on one wall said nothing about which end
     * was which: undo removed "a point", the segment that disappeared was at the *near* end of the
     * tube, and the operator had to remember which of six identical dots that had been. A larger
     * dot on the last node is the same information the rubber band carries - this is where the run
     * continues - and it moves with every tap.
     */
    const val CURRENT_RADIUS_METERS: Float = 0.035f

    /**
     * One mesh holding a sphere at every point.
     *
     * @param points node positions in ARCore world space, in the order they were measured.
     * @param radius marker radius; the caller only ever passes [RADIUS_METERS], the parameter exists
     *   so a test can assert the size without reproducing the constant.
     * @param segments longitudinal segments per sphere; 8 is four stacks of eight and reads round
     *   at marker size while costing 64 triangles each.
     * @return the merged mesh, or [MeshData.EMPTY] when there is nothing to draw.
     */
    fun markers(
        points: List<Vec3>,
        radius: Float = RADIUS_METERS,
        segments: Int = 8,
    ): MeshData {
        var mesh = MeshData.EMPTY
        for (point in points) {
            // A pose ARCore could not resolve is a NaN. A NaN vertex would poison the bounding box
            // and take the whole marker mesh - every other point with it - off the screen.
            if (!point.isFinite() || !radius.isFinite() || radius <= 0f) continue
            mesh = mesh.mergedWith(TubeMeshGenerator.sphere(point, radius, segments))
        }
        return mesh
    }
}

/** True when every component is a real number; a NaN would reach the GPU as an invisible mesh. */
private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
