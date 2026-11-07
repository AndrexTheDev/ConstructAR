/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.geo.PolylineMetrics
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
 *
 * A saved run also keeps the warnings that belong to it (finding 279). Before this list carried
 * notices, the AR screen questioned a weak point until the run was saved and the project screen
 * then presented that run exactly like a measured one. The protocol and the bill of materials
 * still knew, but the screen where the operator looks for the run did not. `UNCERTAIN` comes from
 * the same [PolylineMetrics] count as procurement, not from a second confidence threshold here.
 * It is a list rather than one nullable badge because a recovered run can be uncertain too: one
 * fact must never hide the other.
 */
internal object RunList {

    /** Something the row has to say in warning colour, independently of its name and metres. */
    enum class Notice {
        /** At least one point needs review under the same rule the bill of materials uses. */
        UNCERTAIN,

        /** Lengths survive an interruption; the old AR world position does not. */
        RECOVERED,
    }

    /** One row: the run itself, its stored name, its reported metres and what still needs care. */
    data class Row(
        val route: ConduitRoute,
        val identity: String,
        val meters: Float,
        val notices: List<Notice>,
    )

    /** The project's runs in the order the project stores them, one row each. */
    fun rows(project: ProjectRecord): List<Row> = project.routes.map { route ->
        val metrics = PolylineMetrics.measure(route)
        val notices = buildList {
            if (metrics.lowConfidenceNodes > 0) add(Notice.UNCERTAIN)
            if (route.recovered) add(Notice.RECOVERED)
        }
        Row(
            route = route,
            identity = route.label,
            meters = project.calibratedMeters(route),
            notices = notices,
        )
    }
}
