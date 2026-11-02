/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

/**
 * Whether the cache copy of a site photo can be removed.
 *
 * The camera has no storage permission here: the picture is written into the app's own cache
 * through the `FileProvider`, and the app then copies it into private storage under the entry's
 * token. Nothing ever removed the cache copy, so **every** documentation photo was stored twice -
 * a full-size duplicate of the site's photographs in a folder that only the system empties when
 * the phone is already under pressure. On a room with twenty pictures that is tens of megabytes
 * the operator cannot see and cannot delete, in an app whose whole storage story is "it fits on a
 * cheap phone" (finding 128).
 *
 * Two cases delete, one does not:
 *  * the operator cancelled the camera - there is no picture, only an empty file;
 *  * the copy into private storage succeeded - the file is now redundant;
 *  * the copy **failed** - this cache file is the only existing copy, so it stays.
 */
internal object CaptureFile {

    fun shouldDelete(captured: Boolean, attached: Boolean): Boolean = !captured || attached
}
