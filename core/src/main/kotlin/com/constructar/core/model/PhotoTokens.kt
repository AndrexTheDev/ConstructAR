/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.model

/** What a stored photo belongs to. */
enum class PhotoKind {
    /** The room photo of the project itself. */
    ROOM,

    /** A diary entry of the field book. */
    DIARY,

    /** A defect of the field book. */
    DEFECT,
}

/**
 * The names photos are stored under: `<project id>` for the room, `<project id>__diary_<entry id>`
 * and `<project id>__defect_<entry id>` for the field book.
 *
 * The name is the whole contract. It has to survive a backup zip, a restore onto a new phone and
 * a cascade delete - all three only ever see a file name, never a database row. Keeping the
 * format in one place (instead of three inline `substringBefore` calls) is what makes it testable
 * without an Android device.
 */
object PhotoTokens {

    /** Two underscores cannot appear in a project or entry id, so the name has exactly one split. */
    const val SEPARATOR = "__"

    private const val DIARY_MARK = "diary_"
    private const val DEFECT_MARK = "defect_"

    fun forRoom(projectId: String): String = projectId

    fun forDiary(projectId: String, entryId: String): String =
        projectId + SEPARATOR + DIARY_MARK + entryId

    fun forDefect(projectId: String, defectId: String): String =
        projectId + SEPARATOR + DEFECT_MARK + defectId

    /** The project a token belongs to; also the prefix the cascade delete matches on. */
    fun projectOf(token: String): String = token.substringBefore(SEPARATOR)

    fun kindOf(token: String): PhotoKind = when {
        token.contains(SEPARATOR + DIARY_MARK, ignoreCase = true) -> PhotoKind.DIARY
        token.contains(SEPARATOR + DEFECT_MARK, ignoreCase = true) -> PhotoKind.DEFECT
        else -> PhotoKind.ROOM
    }

    /** The diary entry or defect id, or `null` for a room photo. */
    fun entryOf(token: String): String? = when (kindOf(token)) {
        PhotoKind.ROOM -> null
        PhotoKind.DIARY -> token.substringAfter(SEPARATOR + DIARY_MARK)
        PhotoKind.DEFECT -> token.substringAfter(SEPARATOR + DEFECT_MARK)
    }

    /** True for the room photo, which is stored as the bare project id. */
    fun isRoom(token: String): Boolean = kindOf(token) == PhotoKind.ROOM
}
