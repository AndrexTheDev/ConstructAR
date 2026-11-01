/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

/**
 * The label a finished run gets.
 *
 * Deliberately **not** translated, and deliberately built here rather than in a screen: it is
 * persisted into the project file and it is what the CSV, the PDF, the bill of materials and the
 * 3D nodes all name the run by. A German phone and a Spanish phone must both write `CABLE 16 #3`,
 * or the same project renamed on another device would read as a different run. It is an
 * identity, not a sentence - which is why it is built from the spec and a counter and contains
 * no words at all.
 */
object RouteLabels {

    /**
     * `CABLE 16 #3` for the next run, given how many the project already has.
     *
     * @param existingRoutes runs already stored; the label counts from 1, so the first run of a
     *   project is `#1`.
     */
    fun next(spec: ConduitSpec, existingRoutes: Int): String =
        "${spec.kind.name} ${spec.sizeLabel} #${existingRoutes + 1}"
}
