/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app

import android.app.Application
import com.constructar.app.diagnostics.Diagnostics

/**
 * The application object exists for one reason: diagnostics have to be running before anything can
 * fail (finding 256).
 *
 * A crash handler installed from `MainActivity.onCreate` misses every failure that happens while
 * the activity is coming up - which, on a phone whose ARCore service is not answering, is most of
 * them. Everything else in this app stays out of a static context on purpose; this is the one
 * place that runs before any of it exists.
 */
class ConstructArApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}
