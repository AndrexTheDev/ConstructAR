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

    /** Scanning normally: the coverage gauge. */
    PROGRESS,
}

/**
 * The scan hint for the current frame.
 *
 * [ScanHint.FULL] outranks the instruction and the gauge: a budget spent before the first
 * extracted mesh is still a budget spent, and the operator must not be told to keep walking.
 */
internal fun scanHint(
    showScanner: Boolean,
    ready: Boolean,
    hitBlockBudget: Boolean,
    meshVersion: Int,
): ScanHint = when {
    !showScanner -> ScanHint.NONE
    !ready -> ScanHint.UNAVAILABLE
    hitBlockBudget -> ScanHint.FULL
    meshVersion == 0 -> ScanHint.INSTRUCTION
    else -> ScanHint.PROGRESS
}
