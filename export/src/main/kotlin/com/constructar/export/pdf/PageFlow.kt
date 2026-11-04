/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.export.pdf

/**
 * Where the next line of a note goes, and when it no longer fits.
 *
 * The protocol draws its notes — the confidence sentence, the waste factor, the list of runs
 * that are in the paperwork but not in the model — as wrapped grey text under a table. Their
 * length is not the writer's to bound: the last of those sentences is built from the operator's
 * own run labels (`namedRunsNote` joins all of them), so a job with a dozen unusual runs asks
 * for four lines under a table that stopped one row above the footer.
 *
 * Until finding 174 the notes were drawn as if each were exactly one line: `drawNote` returned
 * where the caller could continue, and two call sites threw that value away — the procurement
 * note and the exclusion note under it printed on the same baseline, two sentences on top of
 * each other — while none of them could turn the page at all.
 *
 * The arithmetic lives here because it is the part that can be tested without a `PdfDocument`:
 * a line fits when its baseline is [lineHeight] above the [bottom] of the text area.
 */
internal class PageFlow(
    /** Baseline of the first line of a fresh page. */
    val top: Int,
    /** How far the baseline moves per line. */
    val lineHeight: Int,
    /** The last baseline that still clears the footer. */
    val bottom: Int,
) {

    /** True when a line whose baseline would be [y] still sits above the footer. */
    fun fits(y: Int): Boolean = y + lineHeight <= bottom
}
