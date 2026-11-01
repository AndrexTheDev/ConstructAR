/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.constructar.app.R
import com.constructar.arengine.route.RouteWarning
import com.constructar.arengine.session.ArReadiness
import com.constructar.arengine.tracking.Guidance
import com.constructar.arengine.tracking.TrackingStatus
import com.constructar.core.model.ConduitKind
import com.constructar.core.model.HitSource
import com.constructar.core.model.SurfaceKind

/**
 * Localisation seam between the engines and the UI.
 *
 * `:core` and `:ar-engine` are pure Kotlin/Android libraries with no resources: they describe
 * *what* happened (a [SurfaceKind], a [HitSource], a [Guidance] code) and never *how to say it*.
 * The app alone turns those codes into `stringResource` lookups, which is why every coaching
 * line, surface name and hit tier exists in all six shipped languages and why the engines stay
 * unit-testable without an Android resource context.
 */

/** "Wall" / "Wand" / "Pared" / … */
@Composable
fun SurfaceKind.label(): String = stringResource(
    when (this) {
        SurfaceKind.WALL -> R.string.surface_wall
        SurfaceKind.FLOOR -> R.string.surface_floor
        SurfaceKind.CEILING -> R.string.surface_ceiling
        SurfaceKind.UNKNOWN -> R.string.surface_unknown
    },
)

/** Which tier of the hit cascade produced a node: "Plane", "Depth point", "Depth ray", "Feature point". */
@Composable
fun HitSource.tierLabel(): String = stringResource(
    when (this) {
        HitSource.PLANE_POLYGON -> R.string.tier_plane
        HitSource.AR_DEPTH_POINT -> R.string.tier_depth_point
        HitSource.DIRECT_DEPTH_RAYCAST -> R.string.tier_direct_depth
        HitSource.FEATURE_POINT -> R.string.tier_feature_point
        HitSource.MANUAL -> R.string.tier_manual
    },
)

/** "Cable" / "Pipe" / "Duct" — the service being routed. */
@Composable
fun ConduitKind.routingLabel(): String = stringResource(
    when (this) {
        ConduitKind.CABLE -> R.string.routing_cable
        ConduitKind.PIPE -> R.string.routing_pipe
        ConduitKind.DUCT -> R.string.routing_duct
    },
)

/** The coaching line for the current tracking verdict, or null when there is nothing to say. */
@Composable
fun Guidance.message(status: TrackingStatus): String? = when (this) {
    Guidance.NONE -> null
    Guidance.LOST -> stringResource(R.string.tracking_loss)
    Guidance.SEARCHING -> stringResource(R.string.tracking_searching)
    Guidance.LIMITED -> stringResource(R.string.tracking_limited)
    Guidance.FAILURE_REASON -> stringResource(R.string.tracking_failure_reason, status.failureDetail.orEmpty())
    Guidance.THERMAL -> stringResource(R.string.tracking_thermal)
    Guidance.WEAK_DEPTH -> stringResource(R.string.depth_weak)
    Guidance.DARK -> stringResource(R.string.low_light_warning)
}

/** Non-fatal routing advice, or null when the run is sound. */
@Composable
fun RouteWarning.message(): String? = when (this) {
    RouteWarning.NONE -> null
    RouteWarning.TOO_CLOSE -> stringResource(R.string.route_warning_too_close)
    RouteWarning.UNPINNED -> stringResource(R.string.route_warning_unpinned)
    RouteWarning.BEND_RADIUS_TOO_TIGHT -> stringResource(R.string.route_warning_bend_radius)
    RouteWarning.SURFACE_JUMP -> stringResource(R.string.route_warning_surface_jump)
}

/** Why AR cannot start, in the operator's language; null when it can. */
@Composable
fun ArReadiness.message(): String? = when (this) {
    ArReadiness.Ready, ArReadiness.InstallRequested -> null
    is ArReadiness.Unsupported -> stringResource(R.string.ar_unavailable_unsupported)
    is ArReadiness.NeedsInstall -> stringResource(R.string.ar_unavailable_needs_install)
    ArReadiness.UserDeclined -> stringResource(R.string.ar_unavailable_declined)
    ArReadiness.CameraPermissionMissing -> stringResource(R.string.ar_camera_permission)
    is ArReadiness.Failure -> stringResource(R.string.ar_unavailable_failure)
}
