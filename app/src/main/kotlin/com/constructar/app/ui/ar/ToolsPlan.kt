/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * Why a tool in the drawer is grey, in one place (finding 271).
 *
 * `ActionGating` already did this for the two buttons that used to be on the sheet, and the ring
 * kept its sentence when it moved into the drawer. The other two did not: "Same place?" is grey
 * until the pose graph holds a single node, and "Marker check" is grey on every phone whose native
 * pipeline has no marker knobs at all - and both said nothing. A dimmed Material button is
 * indistinguishable from a broken one, which is not a theory: it is the exact reading the first
 * device report gave the two buttons that *did* have this treatment (finding 258, "funktionieren
 * nicht").
 *
 * The invariant is enforced by the type rather than by discipline: a [ToolState] cannot be empty of
 * a reason while it is disabled, so a new tool cannot be added without one. Which reason a state
 * gets is decided here and nowhere else; the sentence lives in the resources.
 */
internal object ToolsPlan {

    /** The ring waits for the same three points `ActionGating` names - one threshold, not two. */
    fun ring(nodeCount: Int): ToolState = when {
        nodeCount >= ActionGating.NODES_FOR_A_LOOP -> ToolState(enabled = true)
        else -> ToolState(enabled = false, reason = ToolReason.RING_NEEDS_POINTS)
    }

    /**
     * "Same place?" compares two visits to one spot, and the visits are keyframes.
     *
     * With an empty graph there is nothing to compare, and no sentence could make the button work -
     * the fix is the walkthrough, which is what the reason says.
     */
    fun loopCheck(graphNodes: Int): ToolState = when {
        graphNodes > 0 -> ToolState(enabled = true)
        else -> ToolState(enabled = false, reason = ToolReason.LOOP_NEEDS_ROOM)
    }

    /** A phone whose native pipeline carries no marker knobs cannot read a printed tag, ever. */
    fun marker(available: Boolean): ToolState = when {
        available -> ToolState(enabled = true)
        else -> ToolState(enabled = false, reason = ToolReason.MARKER_NOT_AVAILABLE)
    }
}

/**
 * Whether a tool can be used, and - when it cannot - why.
 *
 * `require`, not a comment: the standing rule is that a disabled control explains itself, and the
 * cheapest way to keep a rule is to make the other state unrepresentable.
 */
internal data class ToolState(val enabled: Boolean, val reason: ToolReason? = null) {
    init {
        require(enabled == (reason == null)) {
            "a tool is either usable or grey with a reason - never both and never neither"
        }
    }
}

/** The three answers a grey tool can give. Resolved to a sentence at composition, never here. */
internal enum class ToolReason {
    /** The ring needs three points. */
    RING_NEEDS_POINTS,

    /** There is no room capture, so there are no two visits to compare. */
    LOOP_NEEDS_ROOM,

    /** This device's pipeline cannot read a marker at all. */
    MARKER_NOT_AVAILABLE,
}
