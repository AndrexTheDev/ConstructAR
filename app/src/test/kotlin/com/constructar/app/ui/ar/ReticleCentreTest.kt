/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reticle samples the centre of the view - so the centre has to follow the view.
 *
 * `configChanges` keeps the measuring screen (and its controller) alive across a rotation, and the
 * old code computed the centre once, while it was `null`: after turning the phone the reticle went
 * on sampling the portrait centre on a landscape view, off the bottom edge, while the `+` button
 * next to it committed the current centre (finding 117).
 */
class ReticleCentreTest {

    @Test
    fun `the first frame centres the reticle on the view`() {
        val centre = ReticleCentre()
        assertEquals(540f to 1_200f, centre.update(1_080, 2_400))
    }

    @Test
    fun `an unchanged size needs no second write`() {
        val centre = ReticleCentre()
        centre.update(1_080, 2_400)
        assertNull(centre.update(1_080, 2_400), "a per-frame volatile write is not worth making")
    }

    /** The defect: rotating the phone must move the sampling pixel with the view. */
    @Test
    fun `a rotation moves the centre with the view`() {
        val centre = ReticleCentre()
        assertEquals(540f to 1_200f, centre.update(1_080, 2_400))
        assertEquals(1_200f to 540f, centre.update(2_400, 1_080))
    }

    @Test
    fun `a view without a size yet leaves the reticle where it was`() {
        val centre = ReticleCentre()
        assertNull(centre.update(0, 0))
        assertEquals(540f to 1_200f, centre.update(1_080, 2_400))
        // A layout pass to zero and back must not produce a spurious re-centre.
        assertNull(centre.update(0, 2_400))
        assertNull(centre.update(1_080, 2_400))
    }
}
