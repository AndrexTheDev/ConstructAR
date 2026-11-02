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
     * `CABLE 16 #3` for the next run, given the labels the project already carries.
     *
     * Numbered **past the highest number in use**, not by counting the runs. Counting meant that
     * deleting run #2 of three made the next run #3 as well: two runs with one name in the project
     * list, the bill of materials, the CSV and the printed protocol - and the customer signing it
     * has no way to know which #3 the second page is talking about (finding 45). Numbers are
     * append-only for the same reason: a protocol that went out as #1, #3, #4 must not later be
     * contradicted by a new #1.
     *
     * @param existingLabels the `label` of every run already stored.
     */
    fun next(spec: ConduitSpec, existingLabels: Collection<String>): String {
        val prefix = "${spec.kind.name} ${spec.sizeLabel} #"
        val used = existingLabels.mapNotNull { label ->
            label.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()
        }
        var number = (used.maxOrNull() ?: 0) + 1
        // A label with a hand-edited or imported number still may not be repeated.
        while (existingLabels.contains(prefix + number)) number++
        return prefix + number
    }
}
