/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.ProjectRecord

/**
 * Which documentation photos get the screen's and the protocol's limited space.
 *
 * Both budgets exist for the heap, not for taste: a thumbnail is a decoded bitmap (~1 MB at the
 * 640 px the field book asks for) and an appendix page holds eight pictures at most. The point of
 * this object is that the budget is spent in an order that can be defended, because before
 * finding 42 it was spent in whatever order the JSON happened to be in: the diary came first, so
 * a defect with a photo - the picture the customer is asked to sign as outstanding - could be the
 * one that did not fit, on the screen and on the page.
 *
 * The order is: open defects, resolved defects, then the diary, newest first within each group.
 * Everything is newest first in the model, so "newest" is the stored order.
 */
object FieldPhotos {

    /** Thumbnails kept in memory for the field book. One screenful with room to scroll. */
    const val SCREEN_BUDGET: Int = 12

    /** Pictures the printed appendix carries. The page budget, not a taste. */
    const val PDF_BUDGET: Int = 8

    /**
     * Every photo token on the job, in the order it deserves to be seen.
     *
     * Only entries that actually have a photo are named: a token is also the name of a file, and
     * asking for one that was never written would spend the budget on nothing.
     */
    fun ordered(project: ProjectRecord): List<String> {
        val open = project.defects.filter { !it.resolved }
        val resolved = project.defects.filter { it.resolved }
        return buildList {
            open.forEach { defect -> defect.photo?.let(::add) }
            resolved.forEach { defect -> defect.photo?.let(::add) }
            project.diary.forEach { entry -> entry.photo?.let(::add) }
        }
    }
}
