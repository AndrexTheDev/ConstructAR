/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * What the bottom of the screen offers, layer by layer (finding 268).
 *
 * The report was *"irgendwie macht das alles keinen Sinn"*, and after four device rounds the shape of
 * the problem is visible in the code: the action sheet had grown to **eight groups of controls at
 * once** — point, close-loop, save, undo, torch, scan toggle, six cable chips, calibration, loop
 * check, marker check — of which two are used every metre and the rest once a week. Finding 267
 * added a ninth (the mode picker). Every one of them was individually justified by a finding and
 * every one of them stole a glove-sized rectangle from the ones that matter.
 *
 * So the sheet gets a hierarchy instead of a pile, and the hierarchy is a rule rather than a taste:
 *
 * 1. **one** primary action, and it is the thing the operator does next — which is not the same
 *    button in a walkthrough as in a measurement;
 * 2. the one action that ends the current run (save), full width, with its refusal underneath;
 * 3. the two corrections used while measuring (undo, torch);
 * 4. the one setting a run has (the cable), as a chip that opens a dialog instead of six chips
 *    competing for the same row;
 * 5. everything rare — ring closure, calibration, loop check, marker check — behind **one**
 *    disclosure that starts closed.
 *
 * This object answers the questions that hierarchy asks, so the composable does not answer them a
 * second time and so the answers can be asserted.
 */
internal object SheetPlan {

    /** The single primary action at the bottom of the screen. */
    enum class Primary {
        /** No point yet: the button that starts the run, and says so. */
        FIRST_POINT,

        /** A run is under way: every further tap lengthens it. */
        POINT,

        /** A walkthrough has nothing to tap, so the primary action is the honest exit. */
        END_CAPTURE,
    }

    /**
     * The primary action for the current state.
     *
     * In a walkthrough there is no point to set, and a primary button that does nothing is the
     * defect this whole screen keeps being reported for — so the walkthrough's primary button is
     * "end walkthrough", which is a real action with a visible consequence (the measuring sheet).
     */
    fun primary(mode: MeasureMode, nodeCount: Int): Primary = when (mode) {
        MeasureMode.CAPTURE -> Primary.END_CAPTURE
        MeasureMode.MEASURE -> if (nodeCount == 0) Primary.FIRST_POINT else Primary.POINT
    }

    /** The run's own actions (save, undo) exist while points are being set. */
    fun showsRunActions(mode: MeasureMode): Boolean = mode.setsPoints

    /**
     * The cable type is chosen per run and is meaningless in a walkthrough — one chip, not six.
     */
    fun showsCableChip(mode: MeasureMode): Boolean = mode.setsPoints

    /**
     * Whether the confirmation card is still owed.
     *
     * It is not a toast: it stays until the operator starts the next run, because a card that
     * disappears after three seconds is one he will miss while he is still rolling up the cable.
     */
    fun showsSavedRun(nodeCount: Int, savedLabel: String?): Boolean =
        savedLabel != null && nodeCount == 0

    /**
     * Whether a tap on the AR surface may place a point (finding 273).
     *
     * The walkthrough's own sentence says *"no points are set during a walkthrough"* — and the tap
     * surface never asked which mode it was in, so a tap on a wall during a walkthrough **did** add a
     * node to the parked run. The sheet said one thing and the glass did another, which is the one
     * thing this screen cannot afford: an operator who taps mid-walkthrough sees a dot appear in a
     * run he thought was finished.
     */
    fun tapPlacesPoint(mode: MeasureMode): Boolean = mode.setsPoints

    /**
     * Whether the drawn run follows the reticle (finding 273).
     *
     * The rubber band is the point of measuring — it is the number and the geometry of the segment
     * being decided (finding 270). In a walkthrough there is no segment being decided, so the run
     * used to swing towards whatever the camera was pointed at while the operator walked the room:
     * a *measured* line that changed shape with the reticle. It is the same question as
     * [tapPlacesPoint] asked about the drawing instead of the tap, and it has to give the same
     * answer.
     */
    fun runFollowsReticle(mode: MeasureMode): Boolean = mode.setsPoints

    /**
     * Whether the rare tools are open. Closed on entry, and not remembered across entries: a
     * drawer that reopens itself next session is a drawer the operator has to close again.
     */
    const val TOOLS_OPEN_ON_ENTRY: Boolean = false
}
