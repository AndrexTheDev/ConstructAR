/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import com.constructar.core.licence.Entitlements

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

    /**
     * How many more rooms the plan's `maxProjects` allows, given what is on the phone.
     *
     * The question the *gate* asks, not only the button: a restore brings rooms in bulk, and
     * counting only the readable ones there let a free installation hold two room files - one that
     * this build cannot parse and one that arrived from a zip - the exact situation the slot rule
     * was fixed for on the home screen (findings 126, 149). `Int.MAX_VALUE` minus a count of rooms
     * on a phone is never near overflow, so a paid plan simply returns a large number.
     */
    fun remaining(entitlements: Entitlements, readable: Int, unreadable: Int): Int =
        (entitlements.maxProjects - used(readable, unreadable)).coerceAtLeast(0)

    /** Whether one more room may be created, by the button or by the gate behind it. */
    fun allowsAnother(entitlements: Entitlements, readable: Int, unreadable: Int): Boolean =
        remaining(entitlements, readable, unreadable) > 0
}
