/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

/**
 * Which sentence the AR overlay owes the operator about the room scan.
 *
 * The one that was missing: the TSDF volume has a hard block budget (`TsdfConfig.maxBlocks`), and
 * when it is spent `findOrCreateBlock` refuses every new block - the mesh stops growing exactly
 * where the operator is still walking along the wall. The engine computes the flag
 * (`IntegrationStats.hitBlockBudget` → `ScanState.hitBlockBudget`) and nothing in the app ever read
 * it, so the HUD kept printing "Room 41 % · 28 k triangles" with a number that had quietly stopped
 * rising: a stuck scan looks exactly like a slow one, and the room the protocol claims to have
 * scanned is only part of the room (finding 97).
 */
internal enum class ScanHint {
    /** No scanner on this screen: the overlay says nothing about scanning. */
    NONE,

    /** The native library is not available on this phone; measuring still works. */
    UNAVAILABLE,

    /** The volume budget is spent: what is captured is captured, and nothing more will be. */
    FULL,

    /** Ready and empty: tell the operator what to do. */
    INSTRUCTION,

    /**
     * The phone cannot keep up: the pipeline is refusing frames, so the mesh is filling in
     * slower than the operator is walking past the walls (finding 170).
     *
     * Ranks below [FULL] and below [INSTRUCTION]: a spent budget is final whatever the queue is
     * doing, and while nothing has been extracted yet the instruction to move slowly is the
     * actionable version of the same advice.
     */
    BEHIND,

    /** Scanning normally: the coverage gauge. */
    PROGRESS,
}

/**
 * The scan hint for the current frame.
 *
 * [ScanHint.FULL] outranks the instruction and the gauge: a budget spent before the first
 * extracted mesh is still a budget spent, and the operator must not be told to keep walking.
 *
 * [ScanHint.BEHIND] is the same class of question one level down: the pipeline refuses frames
 * when integration cannot keep up (`ScanState.framesDropped`), and until finding 170 that number
 * was computed every frame and read by nothing — the operator saw a gauge that climbed too
 * slowly and had no way to know whether to walk slower, clean the lens, or buy a newer phone.
 */
internal fun scanHint(
    showScanner: Boolean,
    ready: Boolean,
    hitBlockBudget: Boolean,
    meshVersion: Int,
    droppedShare: Float = 0f,
): ScanHint = when {
    !showScanner -> ScanHint.NONE
    !ready -> ScanHint.UNAVAILABLE
    hitBlockBudget -> ScanHint.FULL
    meshVersion == 0 -> ScanHint.INSTRUCTION
    droppedShare >= BEHIND_SHARE -> ScanHint.BEHIND
    else -> ScanHint.PROGRESS
}

/**
 * Where "the phone is behind" starts being worth a sentence: one offered frame in six lost.
 *
 * Below that the queue is doing what a phone of this class does while the operator walks, and a
 * warning that appears on every scan is a warning nobody reads. Above it the drop is visible in
 * the geometry itself — walls that stay half-meshed where the operator swept past — and walking
 * slower is the one thing he can do about it.
 */
internal const val BEHIND_SHARE = 0.17f
