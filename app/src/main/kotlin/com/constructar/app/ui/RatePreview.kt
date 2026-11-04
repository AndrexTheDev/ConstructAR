/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.ProjectRecord

/**
 * What the rate field means at the moment the operator is looking at it.
 *
 * Three different numbers live around one text field, and they are easy to confuse: the rate the
 * project stores as its own override, the firm's rate that applies when the project has none, and
 * whatever is typed in the box right now. The screen used to fall back to the *effective* rate -
 * which, when the project had an override, was the override it had pre-filled the field with. So
 * clearing the field (the way to say "bill this job at the firm's rate") left the labour preview
 * showing the override, unchanged; the number only moved after Save. On a screen whose whole job
 * is to show what the job is worth before it is confirmed, that is a preview of the wrong price.
 *
 * A blank field means the firm's rate, and nothing else does. [typedCents] is what gets saved
 * (`null` = no override), [effectiveCents] is what the preview bills at, and [invalid] is a typo -
 * which must never be read as zero, a blank or anything else.
 */
internal data class RatePreview(
    /** What the field holds, in cents; `null` means "no override - use the firm's rate". */
    val typedCents: Int?,
    /** The rate the labour preview is computed with. */
    val effectiveCents: Int,
    /** True when the field holds something that is not a positive amount. */
    val invalid: Boolean,
    /** True when the field differs from what this project stores. */
    val dirty: Boolean,
)

/**
 * Resolves the field against [project] and the firm's rate.
 *
 * [firmRateCents] is the firm default (or the catalogue default when the firm has set none) - it is
 * deliberately *not* the project's override, because the override is exactly the number a blank
 * field has to stop using.
 */
internal fun ratePreview(project: ProjectRecord, typed: String, firmRateCents: Int): RatePreview {
    val input = PricingInput.rate(typed)
    return RatePreview(
        typedCents = input.cents,
        effectiveCents = input.cents ?: firmRateCents,
        invalid = input.invalid,
        dirty = input.cents != project.hourlyRateCents,
    )
}
