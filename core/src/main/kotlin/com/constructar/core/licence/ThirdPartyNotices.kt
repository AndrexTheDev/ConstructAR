/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

package com.constructar.core.licence

/**
 * One third-party component the shipped app contains, with the notice its licence requires.
 *
 * The strings are **not** localised and that is deliberate: a copyright line, a licence name and
 * a product name are legal identifiers. Translating "Copyright (c) 2017 Google LLC" would produce
 * an attribution that does not match the notice the licence asks to be reproduced.
 */
data class ThirdPartyNotice(
    val name: String,
    val licence: String,
    val copyright: String,
) {
    /** What the Help screen shows above the notice - a property, so the UI holds no literal. */
    val title: String get() = "$name - $licence"
}

/**
 * The attributions the app has to carry, in one place.
 *
 * Apache-2.0 (§4) requires a distributed binary to reproduce the notices of what it contains, and
 * BSD-3-Clause requires the copyright notice, the conditions and the disclaimer. Neither existed
 * anywhere in this app or in this repository: the Help screen's "Licence" section only talked
 * about ConstructAR's own licence state, `docs/LICENSING.md` said the attribution was "to be added
 * before release", and two release-checklist items asked for an in-app licences screen that could
 * not be ticked because there was none (finding 109).
 *
 * `scripts/verify_sources.py` (rule 20) reads the names out of `docs/RELEASE_CHECKLIST.md` and
 * fails if one of them is missing here, so the list cannot drift from the promise. The app shows
 * this list on the Help screen; `ThirdPartyNoticesTest` pins the licence names that are allowed to
 * appear (no copyleft, no non-free module).
 */
object ThirdPartyNotices {

    /** Licence identifiers this product may ship under. Anything else fails the unit test. */
    val ALLOWED_LICENCES: Set<String> = setOf("Apache-2.0", "BSD-3-Clause", "Google ARCore SDK licence")

    val ALL: List<ThirdPartyNotice> = listOf(
        ThirdPartyNotice(
            name = "SceneView",
            licence = "Apache-2.0",
            copyright = "Copyright (c) 2021 Thomas Gorisse and SceneView contributors",
        ),
        ThirdPartyNotice(
            name = "Filament",
            licence = "Apache-2.0",
            copyright = "Copyright (c) 2017 Google LLC",
        ),
        ThirdPartyNotice(
            name = "ARCore",
            licence = "Google ARCore SDK licence",
            copyright = "Copyright (c) Google LLC - distributed through Google Play Services for AR",
        ),
        ThirdPartyNotice(
            name = "LiteRT (TensorFlow Lite)",
            licence = "Apache-2.0",
            copyright = "Copyright (c) 2017 The TensorFlow Authors",
        ),
        ThirdPartyNotice(
            name = "Depth Anything V2 Small",
            licence = "Apache-2.0",
            copyright = "Copyright (c) 2024 Lihe Young and contributors",
        ),
        ThirdPartyNotice(
            name = "RTAB-Map (optional back-end)",
            licence = "BSD-3-Clause",
            copyright = "Copyright (c) 2010-2026 Mathieu Labbe, IntRoLab, Universite de Sherbrooke. " +
                "All rights reserved.",
        ),
        ThirdPartyNotice(
            name = "OpenCV (optional, with RTAB-Map)",
            licence = "Apache-2.0",
            copyright = "Copyright (c) 2000-2026 OpenCV Foundation and Intel Corporation",
        ),
        ThirdPartyNotice(
            name = "Kotlin, AndroidX and Jetpack Compose",
            licence = "Apache-2.0",
            copyright = "Copyright (c) The Android Open Source Project, Google LLC and JetBrains s.r.o.",
        ),
    )
}
