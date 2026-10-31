/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.route

import com.constructar.core.math.Quat
import com.constructar.core.math.Vec3
import com.google.ar.core.Pose

/**
 * ARCore [Pose] ↔ :core math, in one place.
 *
 * ARCore's `Pose` is a JNI handle whose accessors are array-based
 * (`getTranslation(float[], int)`, `getRotationQuaternion(float[], int)`); it has **no**
 * `position()`/`rotationQuaternion()` accessor returning an object. Every caller that wants a
 * [Vec3] or a [Quat] therefore goes through the helpers below, which also fix the one thing
 * that is easy to get wrong: ARCore stores quaternions as `[x, y, z, w]` while :core's [Quat]
 * is `(w, x, y, z)`.
 */
internal fun Pose.positionVec(): Vec3 {
    val t = translation
    return Vec3(t[0], t[1], t[2])
}

/** ARCore's raw `[x, y, z, w]` quaternion — for callers that hand it straight to Filament. */
internal fun Pose.rotationQuaternionArray(): FloatArray {
    val q = FloatArray(4)
    getRotationQuaternion(q, 0)
    return q
}

/** ARCore's `[x, y, z, w]` quaternion as a :core [Quat] `(w, x, y, z)`. */
internal fun Pose.toCoreQuat(): Quat {
    val q = rotationQuaternionArray()
    return Quat(q[3], q[0], q[1], q[2])
}
