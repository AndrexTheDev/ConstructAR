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
 * Timestamps the way the operator's phone writes them.
 *
 * The *labels* here go through [DateFormat] with the device's own locale and 12/24-hour
 * convention, so a Polish phone prints `31.10.2026, 14:05` and an American one `10/31/26, 2:05 PM`
 * without a single format string in the resources. `java.time` is off the table: minSdk is 24.
 *
 * The one exception is [formatDayLabel], the fixed numeric day: it is a field, not prose, so it
 * is pinned to Gregorian Latin digits (see its own note - the device locale also chooses the
 * *calendar*, and a Thai phone would otherwise print the Buddhist year).
 *
 * Used by the site file and by the protocol, so a diary entry reads the same on screen and on
 * the page the customer signs.
 */

/** Date only, medium style: `31 Oct 2026`. */
internal fun formatDateLabel(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

/** Time only, short style: `14:05` or `2:05 PM`. */
internal fun formatTimeLabel(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

/** Date and time, short style — one diary line. */
internal fun formatDateTimeLabel(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

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
