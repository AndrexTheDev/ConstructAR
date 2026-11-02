/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Timestamps the way the operator's phone writes them - the clock, not the calendar.
 *
 * Two rules, deliberately different from each other:
 *
 *  * a **clock time** follows the device's 12/24-hour convention (`14:05` or `2:05 PM`), because
 *    that is how the operator already reads a watch, and the app never overrules a phone setting
 *    the owner chose;
 *  * a **date** is pinned to Gregorian Latin digits, `12.05.2026`, on every surface ([formatDayLabel]).
 *
 * The date is pinned because the locale also chooses the *calendar* and the *month names*: on a
 * Thai phone `DateFormat.getDateInstance` prints the Buddhist year, so the protocol's date column
 * read `12 พ.ค. 2569` - 543 years in the future, in a document the customer signs - and on a Polish
 * phone it read `12 maj 2026` in an otherwise English protocol. The home screen and the paywall
 * were already pinned; the field book and the site file were not, so the same day appeared twice in
 * two formats one tap apart (finding 121).
 *
 * `java.time` is off the table: minSdk is 24.
 *
 * Used by the site file and by the protocol, so a diary entry reads the same on screen and on
 * the page the customer signs.
 */

/** Time only, short style: `14:05` or `2:05 PM` - the device's own 12/24-hour convention. */
internal fun formatTimeLabel(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

/** A day and a clock time on one diary line: `12.05.2026, 14:05`. */
internal fun formatDateTimeLabel(epochMs: Long): String =
    formatDayLabel(epochMs) + ", " + formatTimeLabel(epochMs)

/**
 * The fixed numeric day label, `01.11.2026`.
 *
 * `Locale.ROOT`, unlike the labels above, because this one is not prose: it is the same eight
 * characters in every language the app speaks, and it must be **Gregorian**. A `SimpleDateFormat`
 * built from the device locale picks up that locale's calendar, so on a Thai phone the project
 * list and the paywall printed the Buddhist year - a subscription "valid until 01.11.2569", 543
 * years of licence on a `dd.MM.yyyy` field. `Locale.ROOT` also fixes the digit shapes, so the
 * label reads the same as the lengths next to it.
 *
 * @param timeZone the zone the epoch is to be read in. Epoch *days* (the licence expiry) are UTC
 *   by contract, so the paywall passes UTC; a project's creation time is a local moment.
 */
internal fun formatDayLabel(epochMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).apply {
        this.timeZone = timeZone
    }.format(Date(epochMs))

/**
 * Whether [endMs] falls on a different **local** day than [startMs].
 *
 * A session is two instants, and both the field book and the protocol print the *start's* date with
 * the two clock times: a shift from 22:00 to 01:30 therefore read
 * `12.05.2026 | 22:00 | 01:30 | 3 h 30 min` - a row that ends before it begins, on the page the
 * customer signs, next to a duration that contradicts it. The end cell says "(next day)" instead
 * (finding 120).
 *
 * Compared as **formatted local dates** rather than by arithmetic on 86 400 000 ms: a daylight
 * saving change makes one local day 23 or 25 hours long, so a 24-hour shift can sit inside a
 * single local day, and two instants two minutes apart can straddle two. Only the calendar knows.
 */
internal fun endsOnLaterDay(startMs: Long, endMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Boolean =
    formatDayLabel(endMs, timeZone) != formatDayLabel(startMs, timeZone)
