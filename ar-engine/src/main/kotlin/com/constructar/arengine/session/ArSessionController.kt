/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.arengine.session

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

/**
 * Everything that can stop AR from starting, modelled explicitly so the UI can say something
 * actionable instead of showing a black screen. Every branch maps to a sentence a worker can
 * act on — "install the update", "this tablet has no AR" — never a generic "AR failed".
 *
 * The [detail] carries ARCore's own message for the log; the user-facing wording comes from a
 * string resource chosen by the branch, so it exists in every shipped language.
 */
sealed interface ArReadiness {
    /** Ready to start a session. */
    data object Ready : ArReadiness

    /** ARCore is installed but needs an update; Play is opening. */
    data object InstallRequested : ArReadiness

    /** Hardware does not support ARCore (or the required sensors are missing). */
    data class Unsupported(val detail: String = "") : ArReadiness

    /** ARCore APK missing/too old: needs a Play Store install/update. */
    data class NeedsInstall(val detail: String = "") : ArReadiness

    /** The user declined the install; show a rationale with a retry. */
    data object UserDeclined : ArReadiness

    /** Camera permission missing — ask for it. */
    data object CameraPermissionMissing : ArReadiness

    data class Failure(val detail: String = "") : ArReadiness

    val canStartSession: Boolean get() = this is Ready
}

/**
 * ARCore **policy and readiness** for ConstructAR.
 *
 * ## What this class deliberately no longer does
 *
 * It does not create, configure, resume, pause or close a [Session]. SceneView 4.63.0 owns the
 * session lifecycle (`ARSceneView` creates it, configures it from its typed parameters, and
 * destroys it on disposal), and a second owner meant two `configure()` calls racing on one
 * session plus a `close()` the renderer did not expect. What remains is the part SceneView
 * cannot know: ConstructAR's offline policy, applied through the typed
 * `sessionConfiguration` callback — which SceneView invokes *before* `Session.configure()`,
 * the only point where ARCore accepts new settings.
 *
 * Policy encoded here:
 *  * **Offline**: cloud anchors, instant placement, geospatial and streetscape are off. No
 *    ARCore feature in this app talks to a Google service.
 *  * **Depth**: `RAW_DEPTH_ONLY` when supported (the un-smoothed millimetre image the TSDF
 *    needs), else `AUTOMATIC`, else `DISABLED` — with [depthAvailable] telling the UI which.
 *  * **Light**: ambient-intensity estimation feeds the automatic torch and the "too dark"
 *    warning.
 */
class ArSessionController(private val context: Context) {

    /** True when the most recently configured session had depth enabled. */
    @Volatile
    var depthAvailable: Boolean = false
        private set

    /** True when the device supports `Config.FlashMode.TORCH` on the back camera. */
    @Volatile
    var torchSupported: Boolean = false
        private set

    /**
     * Mutates [config] into ConstructAR's policy. Passed straight to
     * `ARSceneView(sessionConfiguration = ...)`; it must not call `session.configure()`
     * itself, and must not touch Compose state.
     */
    @Suppress("DEPRECATION") // ARCore offers no undeprecated replacement for the probe below.
    fun applyPolicy(session: Session, config: Config) {
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        config.depthMode = when {
            session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
            else -> Config.DepthMode.DISABLED
        }
        config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        config.cloudAnchorMode = Config.CloudAnchorMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        config.geospatialMode = Config.GeospatialMode.DISABLED
        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED
        config.augmentedFaceMode = Config.AugmentedFaceMode.DISABLED
        // NOTE: flashMode is deliberately NOT touched here. SceneView applies the typed
        // `flashMode` parameter *before* this callback, and this callback wins — writing OFF
        // would silently disable the torch on every reconfiguration.

        depthAvailable = config.depthMode != Config.DepthMode.DISABLED
        // ARCore ships no `Session.isFlashModeSupported(FlashMode)` getter (unlike
        // `isDepthModeSupported`): the supported way to probe is `Session.isSupported(config)`
        // with the candidate mode set. The previous mode is restored immediately, because
        // SceneView applied the caller's `flashMode` before this callback ran.
        torchSupported = runCatching {
            val previous = config.flashMode
            config.flashMode = Config.FlashMode.TORCH
            val supported = session.isSupported(config)
            config.flashMode = previous
            supported
        }.getOrDefault(false)
    }

    /**
     * Checks ARCore availability *without* creating a session. Suspending because the Play
     * install flow starts an activity and must not block the main thread.
     */
    suspend fun checkReadiness(activity: Activity?, hasCameraPermission: Boolean): ArReadiness {
        if (!hasCameraPermission) return ArReadiness.CameraPermissionMissing
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (error: Throwable) {
            Log.e(TAG, "checkAvailability failed", error)
            return ArReadiness.Failure(error.message ?: "")
        }
        // While the query is still in flight, Play has not answered yet: treat as ready and
        // let session creation produce the definitive answer.
        if (availability.isTransient) return ArReadiness.Ready
        if (!availability.isSupported) return ArReadiness.Unsupported("availability=${availability.name}")
        val installer = activity ?: return ArReadiness.Ready
        return try {
            when (ArCoreApk.getInstance().requestInstall(installer, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> ArReadiness.Ready
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> ArReadiness.InstallRequested
            }
        } catch (error: UnavailableDeviceNotCompatibleException) {
            ArReadiness.Unsupported(error.message ?: "")
        } catch (error: UnavailableSdkTooOldException) {
            ArReadiness.NeedsInstall(error.message ?: "")
        } catch (error: UnavailableUserDeclinedInstallationException) {
            ArReadiness.UserDeclined
        } catch (error: Throwable) {
            Log.e(TAG, "requestInstall failed", error)
            ArReadiness.Failure(error.message ?: "")
        }
    }

    companion object {
        private const val TAG = "ArSessionController"
    }
}

/**
 * Automatic torch with hysteresis.
 *
 * Construction sites are dim (unlit shells, basements, risers) and ARCore's tracking and the
 * depth sensor both degrade without light. Turning the torch on the moment ambient light dips
 * produces visible flicker, so the controller uses two thresholds and a minimum dwell time:
 * on below [onBelowLux], off above [offAboveLux], and never more than one switch per
 * [minDwellMillis]. It also refuses to turn the torch on when the battery is critically low.
 *
 * The controller owns policy only; the switch is a callback that flips Compose state, and
 * SceneView applies it through the typed `flashMode` parameter.
 */
class TorchController(
    private val switch: (Boolean) -> Boolean,
    private val onBelowLux: Float = 40f,
    private val offAboveLux: Float = 120f,
    private val minDwellMillis: Long = 4_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var isOn: Boolean = false
        private set

    /**
     * True once a switch to *on* was refused by the camera.
     *
     * ARCore refuses the torch on a device without a flash - most tablets - and while another
     * camera client holds the camera. The refusal used to be invisible: the operator tapped the
     * torch, `isOn` stayed false, and nothing said why (finding 106). The screen hides the button
     * while this is true instead of leaving a control that cannot do anything.
     */
    var unavailable: Boolean = false
        private set

    private var lastSwitchMillis: Long = Long.MIN_VALUE / 2
    private var manualOverride: Boolean? = null

    /** Feeds one ambient-intensity sample (ARCore reports 0 when unknown). */
    fun onAmbientIntensity(lux: Float, batteryPercent: Int = 100) {
        if (lux <= 0f) return // ARCore could not estimate — keep the current state.
        if (lux >= offAboveLux && unavailable) {
            // Light came back. The refusal was about this attempt, not about the device for ever,
            // so the flag clears and the next dark room may ask again (finding 106).
            unavailable = false
        }
        val override = manualOverride
        if (override != null) {
            // A manual tap wins until the user taps again, but still respects the dwell time.
            if (override != isOn && dwellElapsed()) apply(override)
            return
        }
        if (lux > offAboveLux && isOn) {
            if (dwellElapsed()) apply(false)
        } else if (lux < onBelowLux && !isOn && !unavailable && batteryPercent > LOW_BATTERY_PERCENT) {
            // `!unavailable`: once the camera has refused the torch, the automatic path stops
            // asking. It used to re-ask on every ambient sample - four seconds apart by the dwell
            // rule, for as long as the AR screen stayed open - on a device that has no flash.
            if (dwellElapsed()) apply(true)
        }
    }

    /** Operator tapped the torch button. Returns the state the torch is in afterwards. */
    fun toggle(): Boolean {
        val desired = !isOn
        // The operator asked again: the camera gets another chance, and a second refusal is
        // reported again rather than remembered from last time.
        unavailable = false
        apply(desired)
        // Only a switch that actually happened is a decision to remember. Recording the override
        // before the attempt meant a device whose camera refused the torch kept a permanent
        // "wants the light on" and re-attempted the switch on every ambient sample, every four
        // seconds, for the rest of the session (finding 106).
        manualOverride = if (isOn == desired) desired else null
        return isOn
    }

    // Two lifecycle hooks used to live here - one to clear the manual override "when a scan
    // finishes", one to force the light off - and nothing in the app ever called either of them.
    // The class documented a lifecycle that did not exist, and the only thing that ever switched
    // the torch off was the AR session being released. They are gone rather than called from a
    // place that would have no effect: in a dark room the automatic path turns the light straight
    // back on (finding 106).

    private fun dwellElapsed(): Boolean = clock() - lastSwitchMillis >= minDwellMillis

    private fun apply(desired: Boolean) {
        if (desired == isOn) return
        if (switch(desired)) {
            isOn = desired
            lastSwitchMillis = clock()
            unavailable = false
        } else if (desired) {
            // Turning the torch *off* cannot fail in a way the operator needs to know about; a
            // refused "on" is the answer the button was hiding.
            unavailable = true
        }
    }

    companion object {
        /** Below this the torch costs more battery than it saves in scan quality. */
        const val LOW_BATTERY_PERCENT = 10
    }
}
