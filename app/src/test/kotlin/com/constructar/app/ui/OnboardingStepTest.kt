/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the introduction moves between its slides.
 *
 * The deck is shown exactly once in the life of the app: `onboardingDone` is persisted the moment
 * the operator leaves it. Moving through it was one-way - *next* and *skip* - so a slide tapped past
 * could not be read again, and the position itself lived in a plain `remember`, which meant a
 * rotation (the obvious thing to do while reading a slide) started the whole deck again at slide
 * one (finding 145).
 */
class OnboardingStepTest {

    /** Three slides, the number the screen's own title list decides. */
    private val last = 2

    @Test
    fun `next stops on the last slide instead of walking past it`() {
        assertEquals(1, OnboardingStep.next(0, last))
        assertEquals(2, OnboardingStep.next(1, last))
        assertEquals(2, OnboardingStep.next(2, last))
    }

    @Test
    fun `back stops on the first slide instead of walking past it`() {
        assertEquals(1, OnboardingStep.previous(2))
        assertEquals(0, OnboardingStep.previous(1))
        assertEquals(0, OnboardingStep.previous(0))
    }

    @Test
    fun `there is only something to go back to after the first slide`() {
        assertFalse(OnboardingStep.canGoBack(0))
        assertTrue(OnboardingStep.canGoBack(1))
        assertTrue(OnboardingStep.canGoBack(2))
    }

    @Test
    fun `the last slide is where the button starts the app`() {
        assertFalse(OnboardingStep.isLast(0, last))
        assertFalse(OnboardingStep.isLast(1, last))
        assertTrue(OnboardingStep.isLast(2, last))
        // A deck of one slide is the last slide immediately: nothing to advance to, nothing to go
        // back to, and the single button is the way in.
        assertTrue(OnboardingStep.isLast(0, 0))
        assertFalse(OnboardingStep.canGoBack(0))
    }
}
