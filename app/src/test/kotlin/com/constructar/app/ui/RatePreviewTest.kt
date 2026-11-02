/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.model.ProjectRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the rate field means.
 *
 * The defect this guards: the field was pre-filled with the project's own override and fell back to
 * the *effective* rate, so clearing it - the way to say "bill this job at the firm's rate" - left
 * the labour preview showing the override until Save. The three numbers have to stay apart:
 * override (55,00 €), firm rate (48,00 €), and nothing typed.
 */
class RatePreviewTest {

    private val firm = 4_800

    private fun project(overrideCents: Int?) = ProjectRecord.empty(id = "p1", name = "Küche")
        .copy(hourlyRateCents = overrideCents)

    @Test
    fun `a blank field on a project without an override bills at the firm rate`() {
        val preview = ratePreview(project(null), typed = "", firmRateCents = firm)
        assertEquals(firm, preview.effectiveCents)
        assertNull(preview.typedCents)
        assertFalse(preview.dirty)
    }

    @Test
    fun `a blank field does not keep billing the override it was pre-filled with`() {
        // The operator deletes "55,00" to fall back to the firm's 48,00 - the preview must follow.
        val preview = ratePreview(project(5_500), typed = "", firmRateCents = firm)
        assertEquals(firm, preview.effectiveCents, "a blank field means the firm's rate, not the override")
        assertNull(preview.typedCents, "blank saves no override")
        assertTrue(preview.dirty, "the field no longer matches what the project stores")
    }

    @Test
    fun `the pre-filled override is the rate until it is changed`() {
        val preview = ratePreview(project(5_500), typed = "55,00", firmRateCents = firm)
        assertEquals(5_500, preview.effectiveCents)
        assertEquals(5_500, preview.typedCents)
        assertFalse(preview.dirty)
    }

    @Test
    fun `a typed rate wins over both`() {
        val preview = ratePreview(project(5_500), typed = "62,50", firmRateCents = firm)
        assertEquals(6_250, preview.effectiveCents)
        assertTrue(preview.dirty)
        assertFalse(preview.invalid)
    }

    @Test
    fun `a typo is an error, and the preview falls back to the firm rate rather than zero`() {
        val preview = ratePreview(project(5_500), typed = "vierzig", firmRateCents = firm)
        assertTrue(preview.invalid)
        assertNull(preview.typedCents)
        assertEquals(firm, preview.effectiveCents)
    }
}
