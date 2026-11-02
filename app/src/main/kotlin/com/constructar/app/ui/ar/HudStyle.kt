/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import androidx.compose.ui.graphics.Color

/**
 * The two transparencies the AR overlay is built from, in one place.
 *
 * The HUD is the only part of the app that draws text over a **camera image**, so its contrast is
 * not a pair of theme colours a designer can check once - it depends on what the camera happens to
 * be pointing at. The worst case is the ordinary one: a white plastered wall in sunlight.
 *
 * At the old values (panel 55 %, secondary text 75 %) that composite measured **3.47:1** - below
 * the 4.5:1 WCAG AA floor the rest of the app is held to, and the hit diagnostics at 60 % measured
 * **2.81:1**, below even the large-text floor. The theme's contrast test could not see it: it
 * compares opaque pairs, and these are values inside the screen, not tokens in the palette.
 *
 * See `HudContrastTest`, which composites these numbers over a white and a black camera rather than
 * trusting the eye.
 */
internal object HudStyle {

    /** Panel behind HUD text, drawn over the camera image. */
    const val PANEL_ALPHA = 0.72f

    /** Ordinary HUD text: units, the running total, "running". */
    const val TEXT_ALPHA = 0.9f

    /** The quietest line the HUD prints - the last-hit diagnostics. */
    const val HINT_ALPHA = 0.85f

    /** The panel itself, over whatever the camera shows. */
    val panel: Color get() = Color.Black.copy(alpha = PANEL_ALPHA)

    /** Ordinary text on [panel]. */
    val text: Color get() = Color.White.copy(alpha = TEXT_ALPHA)

    /** The least important line on the HUD. */
    val hint: Color get() = Color.White.copy(alpha = HINT_ALPHA)

    /** Hairline rules inside the HUD panel - a shape, not text. */
    const val RULE_ALPHA = 0.12f

    /** Body text in the two AR dialogs, which sit on the opaque `#10140C` panel. */
    const val DIALOG_BODY_ALPHA = 0.8f

    /** The dialog's cancel action, one step quieter than the body. */
    const val DIALOG_ACTION_ALPHA = 0.75f

    val rule: Color get() = Color.White.copy(alpha = RULE_ALPHA)

    val dialogBody: Color get() = Color.White.copy(alpha = DIALOG_BODY_ALPHA)

    val dialogAction: Color get() = Color.White.copy(alpha = DIALOG_ACTION_ALPHA)
}
