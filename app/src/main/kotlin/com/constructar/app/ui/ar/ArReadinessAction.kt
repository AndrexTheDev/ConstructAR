/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui.ar

import com.constructar.arengine.session.ArReadiness

/**
 * What the readiness gate can *do* about a readiness answer (finding 146).
 *
 * The gate used to have exactly one button - "Back" - for every readiness answer. For the three
 * answers the operator can actually fix (ARCore missing, ARCore too old, install declined) that is
 * a dead end: the screen tells him to install ARCore from the Play Store and then gives him no way
 * to get there, and because the answer was only ever fetched once per camera permission, coming
 * back from a store install did not change it either. The sentence said "then reopen ConstructAR"
 * because that was literally the only way out.
 *
 * The rest of the answers are not his to fix: [ArReadiness.Unsupported] is the device,
 * [ArReadiness.Failure] is an unknown start-up failure, and the camera branches are handled before
 * this gate is reached - those keep "Back" as their single action.
 */
internal object ArReadinessAction {

    /**
     * True when opening the Play Store is a real next step: ARCore is missing, too old, or the
     * install dialog was declined. [ArReadiness.InstallRequested] counts too - Play is opening on
     * its own, but if the operator dismissed that sheet the same button is the way back to it.
     */
    fun offersInstall(readiness: ArReadiness?): Boolean = when (readiness) {
        null, ArReadiness.Ready, ArReadiness.CameraPermissionMissing -> false
        ArReadiness.InstallRequested, ArReadiness.UserDeclined -> true
        is ArReadiness.NeedsInstall -> true
        is ArReadiness.Unsupported, is ArReadiness.Failure -> false
    }
}
