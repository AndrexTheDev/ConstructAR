/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.scan

/**
 * Decides which frames survive thermal down-sampling: every other one, **counted**.
 *
 * The pipeline used to ask the clock: `frame.timestamp % 2L != 0L` skipped the odd frames. That
 * is the parity of a nanosecond counter maintained by the camera HAL, and nothing in ARCore
 * promises consecutive frames differ by an odd number of nanoseconds. A 30 fps stream whose
 * stamps are quantised to microseconds arrives in steps of 33 333 000 ns — an *even* number — so
 * the parity of every stamp is the parity of the first one: either every frame is skipped, and
 * the mesh silently stops updating on a hot device while the HUD still reports that it is
 * integrating every other frame, or none is skipped, and the thermal saving the mode exists for
 * never happens (finding 169).
 *
 * Counting frames is the thing the mode actually means, and it cannot be influenced by a clock
 * that happens to tick in even steps. The counter is only touched from the render thread, in
 * `onFrame`, so it needs no synchronisation.
 */
internal class ThermalFrameGate {

    private var captured = 0

    /**
     * True when this frame is the one to integrate. Call once per captured frame, in order —
     * the answer alternates, whatever the timestamps look like.
     */
    fun admits(): Boolean = captured++ % STRIDE == 0

    companion object {
        /** One frame integrated out of every [STRIDE]. */
        const val STRIDE = 2
    }
}
