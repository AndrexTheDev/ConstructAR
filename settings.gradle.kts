/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// ConstructAR — offline AR construction routing toolkit.
//
// Module graph (see docs/ARCHITECTURE.md):
//
//   :app  ──► :ar-engine ──► :native-slam   (NDK: TSDF + Surface Nets + pose graph)
//     │           └────────► :core          (pure Kotlin: math, domain, mesh generators)
//     ├──────► :export ────► :core          (CSV / PDF / GLB / PLY writers)
//     └──────► :core
//
// :core is a supporting JVM module (no Android types) so that every geometry /
// measurement rule can be unit-tested on the host without a device.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ConstructAR"

include(":app")
include(":ar-engine")
include(":native-slam")
include(":export")
include(":core")
