/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import java.text.DateFormat
import java.util.Date

/**
 * Timestamps the way the operator's phone writes them.
 *
 * Everything here goes through [DateFormat] with the device's own locale and 12/24-hour
 * convention, so a Polish phone prints `31.10.2026, 14:05` and an American one `10/31/26, 2:05 PM`
 * without a single format string in the resources. `java.time` is off the table: minSdk is 24.
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
