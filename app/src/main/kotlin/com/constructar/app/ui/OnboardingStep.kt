/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * Where the introduction is, and how it moves.
 *
 * The screen that shows the three slides before anything else works kept its position in a plain
 * `remember` and had one control: *next*. Two defects came out of that one line. A turn of the
 * phone - the obvious thing to do while reading a slide - recreated the screen and started the
 * introduction again at slide one, on the deck the operator sees exactly once in the life of the
 * app. And because the only way out was *skip*, which writes `onboardingDone` and never comes back,
 * a tap past a slide he wanted to re-read cost him the whole introduction: there was no way to go
 * back one (finding 145).
 *
 * The step is `rememberSaveable` now and has a *back*, and the arithmetic lives here so it can be
 * asserted without a phone - `last` is passed in rather than counted, because the number of slides
 * belongs to the screen that has the titles, not to a constant that can drift from them.
 */
internal object OnboardingStep {

    /** The slide after [step], stopping on [last] instead of walking past the end. */
    fun next(step: Int, last: Int): Int = (step + 1).coerceAtMost(last)

    /** The slide before [step], stopping on the first instead of walking past the start. */
    fun previous(step: Int): Int = (step - 1).coerceAtLeast(0)

    /** Whether there is a slide to go back to - false on the first one, where a button would lie. */
    fun canGoBack(step: Int): Boolean = step > 0

    /** Whether this is the last slide, i.e. whether the button starts the app instead of advancing. */
    fun isLast(step: Int, last: Int): Boolean = step >= last
}
