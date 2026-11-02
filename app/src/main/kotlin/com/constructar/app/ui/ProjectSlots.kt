/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * How many rooms a plan counts.
 *
 * The free plan allows one room. The list the home screen draws holds the rooms that could be
 * *read*, and a file this build cannot parse is deliberately kept and reported separately - which
 * is right, but it made the limit wrong: an operator whose only room file was damaged saw an empty
 * list, was offered "New room", and ended up with two room files on a plan that allows one
 * (finding 126). A file the app cannot open is still a room on his phone, so it takes the slot.
 */
internal object ProjectSlots {

    /** Rooms in use: the readable ones and the files that are on disk and cannot be read. */
    fun used(readable: Int, unreadable: Int): Int = readable + unreadable
}
