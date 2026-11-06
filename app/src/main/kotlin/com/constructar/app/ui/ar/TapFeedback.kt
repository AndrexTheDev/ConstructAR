/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * What a tap did, and what the screen says about the *next* one (finding 269).
 *
 * The screen has been answering "did that work?" with a number that changes in the corner. On the
 * device that is not an answer: the operator taps a wall, the wall looks identical, and the only
 * trace is a digit in the HUD he is not looking at while he aims. So the feedback is layered by
 * **time**, and each layer answers exactly one question:
 *
 *  * *did my tap arrive* - a ring at the finger, 400 ms ([TapEchoRing] draws it);
 *  * *did it count* - one haptic tick, felt through a glove;
 *  * *what did it do* - one sentence under the step strip for [RESULT_VISIBLE_MS]: "point set",
 *    "point set - pulled onto the edge", or "no surface under the finger";
 *  * *where is the point now* - a dot in the wall that never goes away (`NodeMarkers`);
 *  * *how was it measured* - the diagnostics block, closed by default (finding 268).
 *
 * The sentence and the readiness line share **one** slot: a screen that prints "hold steady" and
 * "point set" in two places is the eight-groups problem again, one line lower down.
 *
 * This object is the rule and nothing else - no strings, no colours, no `android.*` - so the
 * precedence is testable without a camera.
 */
internal object TapFeedback {

    /**
     * How long a tap result stays in the slot.
     *
     * Long enough to read a sentence after looking up from the wall, short enough that it is gone
     * before the next tap and never becomes a permanent line the operator learns to ignore. A toast
     * of three seconds was measured against the same device reports: the operator is still holding
     * the phone up when it disappears, so this one is anchored to the *next* tap instead of to a
     * timer it cannot see - the timer only decides when the sentence stops being the answer to
     * "what just happened".
     */
    const val RESULT_VISIBLE_MS: Long = 2_500L

    /**
     * What the tap did.
     *
     * The three refusals are separate answers rather than one "it did not work", because they ask
     * for three different next moves: aim at a surface, step further along, hold still.
     *
     * @param hasSurface the hit test found a surface; `false` is the case that used to be silent.
     * @param added the run actually grew a node. A tap can hit a wall and still be refused - two
     *   centimetres from the last point, or an anchor ARCore would not pin - and claiming
     *   "point set" over a run that did not change is the exact class of lie this screen is
     *   being cleaned of.
     * @param snapped the reticle was pulled onto a corner or an edge, so the point is not exactly
     *   where the operator aimed (finding 213) - worth its own sentence, because it is the
     *   difference between "my aim was good" and "the app improved my aim".
     */
    fun echo(hasSurface: Boolean, added: Boolean, snapped: Boolean): TapEcho = when {
        !hasSurface -> TapEcho.MISS
        !added -> TapEcho.REFUSED
        snapped -> TapEcho.SNAPPED
        else -> TapEcho.PLACED
    }

    /**
     * What the one slot under the step strip shows.
     *
     * The result wins: it is the answer to something the operator just did. Below it, the slot only
     * ever asks for an *action* ("hold steady"), never describes a success - a permanent "ready"
     * line is decoration, and an empty slot is the resting state. The slot never warns about a
     * missing surface on its own: the reticle sample is the centre of the screen, the tap is where
     * the finger is, and a line complaining about the wrong one is worse than silence. A tap that
     * found nothing says so itself, as a [TapEcho.MISS] result.
     */
    fun slot(resultVisible: Boolean, holding: Boolean): TapSlot = when {
        resultVisible -> TapSlot.RESULT
        holding -> TapSlot.HOLD
        else -> TapSlot.NONE
    }
}

/** What the last tap did. Resolved to a sentence at composition, never here. */
internal enum class TapEcho {
    /** A point was placed on an ordinary surface. */
    PLACED,

    /** A point was placed, and the reticle moved it onto a corner or an edge. */
    SNAPPED,

    /** The tap found no surface, so there is no point, and the run did not change. */
    MISS,

    /**
     * The tap hit a surface and the run refused the point; [com.constructar.arengine.route.RouteWarning]
     * carries the reason, and the slot prints its sentence.
     */
    REFUSED,

    /**
     * The operator took the last point back.
     *
     * Not produced by [TapFeedback.echo] - there is no hit resolution to read - but it belongs in
     * the same slot: undo was the last action on this screen that changed a measurement and said
     * nothing at all. The tube shrank and a dot disappeared somewhere at the near end of a wall,
     * and on a six-point run that is easy to miss while the phone is still pointed at it.
     */
    UNDONE,
}

/** What the one slot under the step strip shows right now. */
internal enum class TapSlot {
    /** Nothing to say: the resting state. */
    NONE,

    /** The result of the last tap, for [TapFeedback.RESULT_VISIBLE_MS]. */
    RESULT,

    /** A surface is under the reticle but the reading is still moving. */
    HOLD,
}
