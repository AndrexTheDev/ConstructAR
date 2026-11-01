/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

import kotlinx.serialization.Serializable

/**
 * The paperwork that surrounds a measurement: who the customer is, how long the job took,
 * what happened on site, and what is still broken.
 *
 * Why this lives in `:core` and is serialised with the project rather than in a database of
 * its own: the tradesman's objection to every "site documentation app" is that the notes live
 * somewhere else than the measurements. One JSON file per room keeps a job portable - copy it,
 * back it up, hand it to a colleague - and keeps the protocol and the evidence in the same
 * document.
 *
 * All three records are additive: a project file written before this existed still decodes,
 * because every field has a default.
 */

/**
 * A defect found on site, as the tradesman describes it to the next person: what, where, how bad.
 *
 * "Resolved" is a flag rather than a deletion because the two questions asked at handover are
 * *is anything still open* and *what was fixed after I wrote it down* — the second one needs
 * the closed items to still be there.
 */
@Serializable
data class DefectItem(
    val id: String,
    val createdAtEpochMs: Long,
    val title: String,
    val location: String = "",
    val severity: DefectSeverity = DefectSeverity.MEDIUM,
    val resolved: Boolean = false,
    /**
     * File token of the documentation photo (`<project>__defect_<id>`), or `null`.
     *
     * A token, not a path: the storage layout belongs to the app, and a project file that is
     * copied to another phone must not carry an absolute path from this one.
     */
    val photo: String? = null,
)

/** Three levels, because a fifth is never used and a fourth is argued about. */
@Serializable
enum class DefectSeverity {
    /** Cosmetic: a scuff, an uneven bead of sealant. */
    LOW,

    /** Has to be fixed before handover, but nobody is in danger. */
    MEDIUM,

    /** Safety or water: cut the power, call the site manager. */
    HIGH,
}

/**
 * One line of the site diary.
 *
 * Deliberately free text with a timestamp and nothing else: every extra field (weather, crew,
 * machine hours) is a field the operator skips in the rain. The timestamp is what makes it
 * evidence.
 */
@Serializable
data class DiaryEntry(
    val id: String,
    val createdAtEpochMs: Long,
    val text: String,
    /** File token of the documentation photo (`<project>__diary_<id>`), or `null`. */
    val photo: String? = null,
)

/**
 * One stretch of working time on this project.
 *
 * [endEpochMs] is `null` while the clock is running; that null is also what makes "is a timer
 * running" answerable without a second flag that can disagree with the list.
 */
@Serializable
data class WorkSession(
    val id: String,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
)

/** Arithmetic for [WorkSession], kept out of the UI so it can be unit-tested on the JVM. */
object WorkTime {

    const val MINUTE_MS: Long = 60_000L
    private const val HOUR_MS: Long = 3_600_000L

    /** `true` when a session is still open — the state the "stop work" button is shown in. */
    fun isRunning(sessions: List<WorkSession>): Boolean = sessions.any { it.endEpochMs == null }

    /** The open session, if any. Sessions are appended in order, so the last one wins. */
    fun runningSession(sessions: List<WorkSession>): WorkSession? =
        sessions.lastOrNull { it.endEpochMs == null }

    /** Duration of one session; an open one runs until [nowMs]. Never negative. */
    fun sessionMillis(session: WorkSession, nowMs: Long): Long =
        ((session.endEpochMs ?: nowMs) - session.startEpochMs).coerceAtLeast(0L)

    /** Everything on this project, including the session that is still running. */
    fun totalMillis(sessions: List<WorkSession>, nowMs: Long): Long =
        sessions.sumOf { sessionMillis(it, nowMs) }

    fun hours(millis: Long): Long = millis / HOUR_MS

    /** Whole minutes after the hours are taken out — 2 h 07 min, never "127 min". */
    fun minutes(millis: Long): Long = (millis % HOUR_MS) / MINUTE_MS
}
