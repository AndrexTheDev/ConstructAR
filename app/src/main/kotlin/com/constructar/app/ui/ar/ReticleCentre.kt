/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * Where the reticle should sample, in screen pixels.
 *
 * The reticle samples the pixel at the centre of the AR view, so its position only has to be
 * recomputed when the view **changes size**. It used to be computed once and then only while it
 * was `null`, and `AndroidManifest` declares `configChanges="orientation|screenSize|…"`: the
 * activity - and with it the controller, the interaction and this point - survives a rotation. So
 * after turning the phone the reticle kept asking ARCore for the **portrait** centre `(540, 1200)`
 * on a `2400 × 1080` view, a pixel below the bottom edge: the preview segment, the "hold steady"
 * reading and the length under the crosshair all belonged to a ray the operator was not aiming
 * along, while the `+` button beside them committed the *current* centre. Two answers from one
 * screen, and the one the operator trusted to decide when to tap was the wrong one (finding 117).
 *
 * Kept as its own object so the rule - "recompute on every size change, write nothing otherwise" -
 * is testable without a camera or a phone.
 */
internal class ReticleCentre {

    private var width: Int = -1
    private var height: Int = -1

    /**
     * The pixel the reticle must sample now, or `null` when nothing needs to change.
     *
     * `null` both when the view has no size yet (a layout pass before the first frame) and when
     * the size is the one already used - so the caller may skip a volatile write per frame.
     */
    fun update(viewWidth: Int, viewHeight: Int): Pair<Float, Float>? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (viewWidth == width && viewHeight == height) return null
        width = viewWidth
        height = viewHeight
        return (viewWidth / 2f) to (viewHeight / 2f)
    }
}
