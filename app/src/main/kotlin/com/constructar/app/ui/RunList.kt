/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.ConduitRoute
import com.constructar.core.model.ProjectRecord

/**
 * The rows of a project's run list, as data (finding 278).
 *
 * The list existed and told the truth about every run except *which* run it was. Its first line is
 * the kind ("Kabel"), its second the cable and size ("NYM-J 3x1,5 · 20 mm") - and two runs of the
 * same cable on the same wall produce two rows that are character-for-character identical apart
 * from the metres. One of them has a delete button under the thumb, and the protocol prints a name
 * for each of them, so the screen was the only place where that name did not exist:
 *
 * ```
 * PDF column 1   CABLE 20 mm #3
 * CSV            CABLE 20 mm #3
 * AR screen      "CABLE 20 mm #3 saved · 3.00 m"     (run_saved)
 * project list   Kabel / NYM-J 3x1,5 · 20 mm          <- the name is missing
 * ```
 *
 * A row cannot be built here without its identity, and the identity is the *stored* label rather
 * than one derived from the cable: a run may carry a name that was imported or corrected by hand,
 * and the list has to show the string that is on the paper, not a fresh guess at it.
 *
 * The metres are the reported ones (finding 277): these rows sit under the project's total, which
 * the calibration corrects, so a row that printed the raw measurement would not add up to it.
 */
internal object RunList {

    /** One row: the run itself, the name that tells it apart, and what it is reported as. */
    data class Row(val route: ConduitRoute, val identity: String, val meters: Float)

    /** The project's runs in the order the project stores them, one row each. */
    fun rows(project: ProjectRecord): List<Row> = project.routes.map { route ->
        Row(
            route = route,
            identity = route.label,
            meters = project.calibratedMeters(route),
        )
    }
}
