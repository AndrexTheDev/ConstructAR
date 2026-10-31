/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

// Root build file. Plugins are declared `apply false` here and applied per module.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * Repository-wide guard: the product is 100 % offline. Any dependency that drags in a
 * telemetry/analytics provider at runtime is rejected at configure time instead of shipping.
 * Extend [OFFLINE_FORBIDDEN_GROUPS] when a new offender is discovered.
 */
val OFFLINE_FORBIDDEN_GROUPS = setOf(
    "com.google.firebase",
    "com.google.android.gms.measurement",
    "com.microsoft.appcenter",
)

allprojects {
    // Ownership metadata for every module (also the `project.group` that a published AAR
    // would carry). All rights reserved - see LICENSE.
    group = "com.constructar"
    version = "1.0.0"
}

/**
 * Static gates that need no Android SDK: copyright headers, the reflection ban, hardcoded UI
 * text, string-resource references, six-locale parity, XML well-formedness and Kotlin
 * bracket balance. `android-build.yml` runs the same script before Gradle starts.
 */
tasks.register<Exec>("verifySources") {
    group = "verification"
    description = "Runs scripts/verify_sources.py (headers, reflection ban, i18n parity)."
    commandLine("python3", "scripts/verify_sources.py", "--verbose")
}

subprojects {
    tasks.withType(Test::class.java).configureEach {
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
