/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.licence.Entitlements
import com.constructar.core.licence.Plan
import org.junit.Test
import kotlin.test.assertEquals

/**
 * What the paywall's trial block may say.
 *
 * Finding 67: the block decided from `trialUsed && trialDaysLeft > 0` - its own computation of "is
 * the trial running" - while the rest of the app reads `entitlements.isTrial`. After a clock
 * rollback the licence rules kill the trial but the day counter still reported days left, so the
 * paywall promised an active trial on a build that granted none. And the button switched itself to
 * "active" before the start was even attempted, so a refusal read as a success.
 */
class TrialBlockTest {

    private fun plan(plan: Plan) = Entitlements.of(plan = plan)

    @Test
    fun `a paid plan shows no trial block at all`() {
        assertEquals(TrialBlock.NONE, trialBlockOf(plan(Plan.MONTHLY), failed = false, used = true))
        assertEquals(TrialBlock.NONE, trialBlockOf(plan(Plan.LIFETIME), failed = true, used = false))
    }

    @Test
    fun `a running trial is shown because the entitlements say so`() {
        assertEquals(TrialBlock.ACTIVE, trialBlockOf(plan(Plan.TRIAL), failed = false, used = true))
        // Even after a failed start of a *second* trial: the state comes from the entitlements.
        assertEquals(TrialBlock.ACTIVE, trialBlockOf(plan(Plan.TRIAL), failed = true, used = true))
    }

    @Test
    fun `a free build offers the trial only when it is still available`() {
        assertEquals(TrialBlock.OFFER, trialBlockOf(plan(Plan.FREE), failed = false, used = false))
        assertEquals(TrialBlock.USED, trialBlockOf(plan(Plan.FREE), failed = false, used = true))
    }

    @Test
    fun `a start that failed says so instead of pretending`() {
        assertEquals(TrialBlock.UNAVAILABLE, trialBlockOf(plan(Plan.FREE), failed = true, used = false))
        // The refusal wins over the "already used" line: it is the truth of this attempt.
        assertEquals(TrialBlock.UNAVAILABLE, trialBlockOf(plan(Plan.FREE), failed = true, used = true))
    }

    /**
     * A trial state that did not verify is not a used trial.
     *
     * The screen said "The trial has already been used" for a phone whose stored trial state had
     * failed its seal - a trial nobody used - and the field that distinguishes the two was
     * computed by the store and shown by nothing (finding 139).
     */
    @Test
    fun `a trial state that did not verify is not called used`() {
        assertEquals(
            TrialBlock.TAMPERED,
            trialBlockOf(plan(Plan.FREE), failed = false, used = true, tampered = true),
        )
        // It also wins over a refused start: that refusal is the consequence, this is the cause.
        assertEquals(
            TrialBlock.TAMPERED,
            trialBlockOf(plan(Plan.FREE), failed = true, used = true, tampered = true),
        )
        // A resolved paid plan still shows nothing at all.
        assertEquals(
            TrialBlock.NONE,
            trialBlockOf(plan(Plan.YEARLY), failed = false, used = false, tampered = true),
        )
    }

    /**
     * The button is not drawn for a trial the rules have already refused.
     *
     * After a clock rollback - or on a build that cannot vouch for itself - `startTrial()` refuses
     * from `trialAvailable`, while the screen offered "Try Pro for 7 days" from `trialUsed`, a raw
     * presence check of the stored key. The button could only ever end in the failure sentence
     * (finding 140).
     */
    @Test
    fun `a trial the rules have already refused is not offered`() {
        assertEquals(
            TrialBlock.UNAVAILABLE,
            trialBlockOf(plan(Plan.FREE), failed = false, used = false, available = false),
        )
        // A trial that really was used keeps its own sentence, even though it cannot restart.
        assertEquals(
            TrialBlock.USED,
            trialBlockOf(plan(Plan.FREE), failed = false, used = true, available = false),
        )
        // And the ordinary case is still an offer.
        assertEquals(
            TrialBlock.OFFER,
            trialBlockOf(plan(Plan.FREE), failed = false, used = false, available = true),
        )
    }
}
