package com.constructar.arengine.session

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.google.ar.core.exceptions.UnsupportedConfigurationException

/**
 * Everything that can stop AR from starting, modelled explicitly so the UI can say something
 * actionable instead of showing a black screen. Every branch maps to a sentence a worker can
 * act on — "install the update", "this tablet has no AR", "the camera is in use by another
 * app" — never a generic "AR failed".
 */
sealed interface ArReadiness {
    /** Ready to `Session(context)`. */
    data object Ready : ArReadiness

    /** ARCore is installed but needs an update; Play is opening. */
    data object InstallRequested : ArReadiness

    /** Hardware does not support ARCore (or the required sensors are missing). */
    data class Unsupported(val detail: String) : ArReadiness

    /** ARCore APK missing/too old: needs a Play Store install/update. */
    data class NeedsInstall(val detail: String) : ArReadiness

    /** The user declined the install; show a rationale with a retry. */
    data object UserDeclined : ArReadiness

    /** Camera permission missing — ask for it. */
    data object CameraPermissionMissing : ArReadiness

    /** Another app holds the camera. */
    data class CameraBusy(val detail: String) : ArReadiness

    data class Failure(val detail: String) : ArReadiness

    val canStartSession: Boolean get() = this is Ready
}

/**
 * Owns the ARCore [Session] lifecycle and its configuration.
 *
 * Rules encoded here:
 *  * **Offline**: cloud anchors, instant placement and any ARCore cloud feature are disabled.
 *  * **Depth**: requested as `AUTOMATIC` only when the device supports it; otherwise the app
 *    degrades to plane-only measurement and says so in the UI (`depthAvailable`).
 *  * **Torch**: `Config.FlashMode.TORCH` is not a per-frame switch — it is a session config
 *    field, so toggling it means re-applying the config (cheap, ~1 ms, but it resets when the
 *    session pauses). See [TorchController] for the policy.
 *  * **Resume ordering**: `cameraNotAvailable` on the *first* resume is usually transient
 *    (another app releasing the camera); retry once before surfacing an error.
 */
class ArSessionController(private val context: Context) {

    var session: Session? = null
        private set

    /** True when the current session was configured with depth enabled. */
    var depthAvailable: Boolean = false
        private set

    /** True when the device currently supports the torch. */
    var torchSupported: Boolean = false
        private set

    /** Set when a session could not be created; consumed by the UI. */
    var lastError: String? = null
        private set

    /**
     * Checks ARCore availability *without* creating a session. Suspending because the Play
     * install flow starts an activity and must not block the main thread.
     */
    suspend fun checkReadiness(activity: Activity, hasCameraPermission: Boolean): ArReadiness {
        if (!hasCameraPermission) return ArReadiness.CameraPermissionMissing
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (error: Throwable) {
            Log.e(TAG, "checkAvailability failed", error)
            return ArReadiness.Failure(error.message ?: "ARCore check failed")
        }
        // While the query is still in flight, Play has not answered yet: treat as ready and
        // let `Session()` produce the definitive answer.
        if (availability.isTransient) return ArReadiness.Ready
        if (!availability.isSupported) {
            return ArReadiness.Unsupported(
                "This device does not support ARCore. The measured route can still be entered manually.",
            )
        }
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> ArReadiness.Ready
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> ArReadiness.InstallRequested
                else -> ArReadiness.Ready
            }
        } catch (error: UnavailableDeviceNotCompatibleException) {
            ArReadiness.Unsupported(error.message ?: "ARCore is not supported on this device")
        } catch (error: UnavailableUserDeclinedInstallationException) {
            ArReadiness.UserDeclined
        } catch (error: Throwable) {
            Log.e(TAG, "requestInstall failed", error)
            ArReadiness.Failure(error.message ?: "ARCore install request failed")
        }
    }

    /** Creates (or re-creates) the session with the ConstructAR configuration. */
    fun ensureSession(): Session? {
        session?.let { return it }
        return try {
            Session(context).also { created ->
                session = created
                applyConfig(created)
            }
        } catch (error: UnavailableArcoreNotInstalledException) {
            lastError = "ARCore is not installed."
            null
        } catch (error: UnavailableApkTooOldException) {
            lastError = "The ARCore app is too old. Update it in the Play Store."
            null
        } catch (error: UnavailableSdkTooOldException) {
            lastError = "This build targets an ARCore version that is no longer supported."
            null
        } catch (error: UnavailableDeviceNotCompatibleException) {
            lastError = "This device is not ARCore-compatible."
            null
        } catch (error: UnsupportedConfigurationException) {
            lastError = "This ARCore version does not support the requested configuration."
            null
        } catch (error: Throwable) {
            Log.e(TAG, "Session creation failed", error)
            lastError = error.message ?: "ARCore session could not be created."
            null
        }
    }

    /**
     * Applies/refreshes the session configuration.
     *
     * @param target an existing session (SceneView creates its own); defaults to the
     *               controller's session, creating one when necessary.
     * @return the configuration that was applied, or null when no session exists.
     */
    fun applyConfig(target: Session? = null): Config? {
        val active = target ?: ensureSession() ?: return null
        val config = Config(active).apply {
            // ── Plane detection: vertical walls and floors/ceilings both matter for routing.
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

            // ── Depth: RAW_DEPTH_ONLY gives the un-smoothed millimetre image (needed for the
            //    TSDF) *and* the confidence image. AUTOMATIC is the safe superset.
            depthMode = when {
                active.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> Config.DepthMode.RAW_DEPTH_ONLY
                active.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> Config.DepthMode.AUTOMATIC
                else -> Config.DepthMode.DISABLED
            }

            // ── Light estimation tells us whether the site is too dark to track, and drives
            //    the automatic torch.
            lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY

            // ── Focus: AUTO keeps close-up cable work sharp on a dark site.
            focusMode = Config.FocusMode.AUTO

            // ── Frame delivery: latest image keeps latency low while scanning.
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

            // ── 100 % offline: no cloud anchors, no instant placement, no geospatial API.
            cloudAnchorMode = Config.CloudAnchorMode.DISABLED
            instantPlacementMode = Config.InstantPlacementMode.DISABLED

            // ── Torch policy starts off; TorchController enables it when the scene is dark.
            flashMode = Config.FlashMode.OFF
        }
        active.configure(config)
        depthAvailable = config.depthMode != Config.DepthMode.DISABLED
        torchSupported = active.isFlashModeSupported(Config.FlashMode.TORCH)
        return config
    }

    /** Sets the torch state; returns true when the change was applied. */
    fun setTorch(enabled: Boolean): Boolean {
        val target = session ?: return false
        if (enabled && !target.isFlashModeSupported(Config.FlashMode.TORCH)) return false
        return try {
            val config = Config(target).apply {
                flashMode = if (enabled) Config.FlashMode.TORCH else Config.FlashMode.OFF
            }
            target.configure(config)
            true
        } catch (error: Throwable) {
            Log.w(TAG, "torch switch failed", error)
            false
        }
    }

    /**
     * Resumes the session. Retries once on `CAMERA_NOT_AVAILABLE` because Android frequently
     * reports it for a few hundred milliseconds after the previous camera owner is gone.
     */
    @Throws(CameraNotAvailableException::class)
    fun resume(maxAttempts: Int = 2) {
        val target = ensureSession() ?: throw CameraNotAvailableException()
        var attempt = 0
        while (true) {
            try {
                target.resume()
                return
            } catch (error: CameraNotAvailableException) {
                attempt++
                if (attempt >= maxAttempts) throw error
                Log.w(TAG, "camera busy, retrying session resume ($attempt/$maxAttempts)")
                Thread.sleep(250)
            }
        }
    }

    fun pause() {
        runCatching { session?.pause() }.onFailure { Log.w(TAG, "pause failed", it) }
    }

    fun close() {
        runCatching { session?.close() }.onFailure { Log.w(TAG, "close failed", it) }
        session = null
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

    private var lastSwitchMillis: Long = Long.MIN_VALUE / 2
    private var manualOverride: Boolean? = null

    /** Feeds one ambient-intensity sample (ARCore reports 0 when unknown). */
    fun onAmbientIntensity(lux: Float, batteryPercent: Int = 100) {
        if (lux <= 0f) return // ARCore could not estimate — keep the current state.
        val override = manualOverride
        if (override != null) {
            // A manual tap wins until the user taps again, but still respects the dwell time.
            if (override != isOn && dwellElapsed()) apply(override)
            return
        }
        if (lux > offAboveLux && isOn) {
            if (dwellElapsed()) apply(false)
        } else if (lux < onBelowLux && !isOn && batteryPercent > LOW_BATTERY_PERCENT) {
            if (dwellElapsed()) apply(true)
        }
    }

    /** Operator tapped the torch button. */
    fun toggle(): Boolean {
        manualOverride = !isOn
        apply(!isOn)
        return isOn
    }

    /** Clears the manual override (e.g. when a scan finishes). */
    fun releaseManualOverride() {
        manualOverride = null
    }

    fun forceOff() = apply(false)

    private fun dwellElapsed(): Boolean = clock() - lastSwitchMillis >= minDwellMillis

    private fun apply(desired: Boolean) {
        if (desired == isOn) return
        if (switch(desired)) {
            isOn = desired
            lastSwitchMillis = clock()
        }
    }

    companion object {
        /** Below this the torch costs more battery than it saves in scan quality. */
        const val LOW_BATTERY_PERCENT = 10
    }
}
