/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core is deliberately Android-free: every geometry, measurement and BOM rule in
// this module runs on the host JVM, so it is unit-testable without a device or
// emulator. Nothing here may import android.* — the build enforces it below.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        // Zero warnings is a product requirement: a deprecation today is a build break
        // tomorrow, and this project cannot afford either. The escape hatch exists only for a
        // toolchain regression (`-Pconstructar.lenientWarnings=true`) and must never be used
        // for a release build - see docs/RELEASE_CHECKLIST.md.
        allWarningsAsErrors.set(
            !providers.gradleProperty("constructar.lenientWarnings").isPresent,
        )
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // NOTE: no `-Xjvm-default` / `-opt-in` flags. `-Xjvm-default=all` is deprecated in
        // favour of the Kotlin 2.x default (interface default methods are generated), and
        // `-opt-in=kotlin.RequiresOptIn` is redundant — with `allWarningsAsErrors`, a
        // deprecation warning about either flag would fail the build.
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

/**
 * Guard: :core must stay a pure-JVM module so the domain rules can be shared with
 * the export/reporting tooling and exercised on CI without an Android SDK.
 */
val forbidAndroidImports by tasks.registering {
    val sources = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sources)
    doLast {
        sources.asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { line, text ->
                    if (text.trimStart().startsWith("import android.")) {
                        error(":core must not depend on Android (${file.name}:${line + 1}): $text")
                    }
                }
            }
    }
}
tasks.named("check") { dependsOn(forbidAndroidImports) }
