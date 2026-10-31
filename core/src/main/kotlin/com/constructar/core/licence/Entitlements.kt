/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

/**
 * What the operator has paid for.
 *
 * The app is *fully* functional while free — scanning, routing, measuring, the bill of
 * materials and a watermarked protocol are not crippled, because an app that cannot prove
 * itself on a real room is never bought. What Pro adds is what a business needs rather than
 * what an operator needs: more than one room, a protocol without the watermark, and the 3D
 * exports a BIM workflow consumes.
 *
 * [TRIAL] is a paid plan for exactly as long as it lasts: it exists so the gating code has one
 * predicate (`isPro`) instead of a second "is trial" branch everywhere.
 */
enum class Plan(val isPaid: Boolean) {
    /** No licence. One project, CSV + watermarked PDF. */
    FREE(false),

    /** Seven days of Pro, started by the operator, no payment details asked for. */
    TRIAL(true),

    MONTHLY(true),
    YEARLY(true),

    /** Paid once, never expires. The plan a one-person business buys. */
    LIFETIME(true),
    ;

    /**
     * Whether this plan can be *sold* — i.e. whether a signed licence may carry it.
     *
     * [TRIAL] deliberately cannot: it is local state the app starts itself, so a licence that
     * claims to be one is a configuration error rather than a purchase, and treating it as valid
     * would let a single minted key masquerade as a permanent Pro licence.
     */
    val isPurchasable: Boolean
        get() = this == MONTHLY || this == YEARLY || this == LIFETIME

    companion object {
        /** Tolerant parser for the `plan` field of a licence payload. */
        fun parse(raw: String?): Plan? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * The capabilities the UI and the export path are allowed to offer.
 *
 * Deliberately a value object computed once from a licence (or its absence) instead of
 * scattered `if (isPro)` checks: every gate in the app asks the *same* object, which is what
 * makes it possible to say truthfully what the free version does.
 */
data class Entitlements(
    val plan: Plan = Plan.FREE,
    /** Last day (epoch day, UTC) the licence is valid; `0` means "never expires". */
    val expiresEpochDay: Long = 0L,
    /** Whole days left, `null` for a lifetime licence or the free plan. */
    val daysLeft: Long? = null,
) {
    val isPro: Boolean get() = plan.isPaid

    val isTrial: Boolean get() = plan == Plan.TRIAL

    /** A free build may keep one room. Deleting it frees the slot — nothing is ever locked away. */
    val maxProjects: Int get() = if (isPro) Int.MAX_VALUE else FREE_PROJECTS

    /** Runs per project are never limited: a half-measured room is worse than useless. */
    val maxRoutesPerProject: Int get() = Int.MAX_VALUE

    /** The protocol PDF is always produced; the free one carries a discreet watermark. */
    val canExportPdf: Boolean get() = true

    val pdfWatermark: Boolean get() = !isPro

    /** GLB/PLY (and therefore the scan mesh and every BIM consumer) is the paid product. */
    val canExportMesh: Boolean get() = isPro

    /** The CSV bill of materials is free: it is the number the customer asks for. */
    val canExportCsv: Boolean get() = true

    fun allowsAnotherProject(currentCount: Int): Boolean = currentCount < maxProjects

    companion object {
        const val FREE_PROJECTS: Int = 1

        /** Length of the no-questions trial, in days. */
        const val TRIAL_DAYS: Long = 7L

        val FREE = Entitlements()

        fun of(plan: Plan, expiresEpochDay: Long = 0L, todayEpochDay: Long = 0L): Entitlements {
            val daysLeft = when {
                expiresEpochDay <= 0L -> null
                else -> (expiresEpochDay - todayEpochDay).coerceAtLeast(0L)
            }
            return Entitlements(plan = plan, expiresEpochDay = expiresEpochDay, daysLeft = daysLeft)
        }
    }
}
