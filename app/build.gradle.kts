/*
 * Copyright (c) 2026 AndrexTheDev <hippie.highho@gmail.com>
 * All rights reserved. Proprietary and confidential - see LICENSE.
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

/**
 * The public half of the licence key, and the one build input that is not in the repository.
 *
 * Until finding 240 the key was a Kotlin constant in `LicencePublicKey.kt` with a demo value and a
 * comment asking a human to swap it before the first sale. Nothing enforced that: a release cut
 * from a checkout that never ran `mint_licence.py --init` verified licences nobody could have
 * signed, and it looked exactly like a working build - the paywall simply did not open. A key that
 * ships is a build *input*, so it is read here, where a build can refuse it:
 *
 *   * `CONSTRUCTAR_PROD_PUBLIC_KEY` (environment, base64url of the DER SPKI) - what a release
 *     machine sets, and what `tools/mint_licence.py --init` now prints;
 *   * `-Pconstructar.prodPublicKey=...` - the same value for a local Gradle run;
 *   * otherwise the demo key, which verifies nothing and makes `verifyLicenceKey` fail a release.
 */
val demoLicenceKey =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEX9y2jKeTryNpA0TJz9C-Ghm8Lxd8ysDaV5EwL3tsc1vv" +
        "G3Xf3sAdjHzWZ7ezf0hOafsBNLYikpXmIhSQFZXusg"

val licenceKey: String =
    (providers.environmentVariable("CONSTRUCTAR_PROD_PUBLIC_KEY").orNull
        ?: providers.gradleProperty("constructar.prodPublicKey").orNull)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: demoLicenceKey

val licenceKeyIsDemo = licenceKey == demoLicenceKey

android {
    namespace = "com.constructar.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.constructar.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Where the Depth Anything V2 Small (Apache-2.0) TFLite model lives once the operator
        // drops it into app/src/main/assets/models/. Kept out of VCS (see .gitignore) because
        // it is a 25 MB binary that must be hash-verified, not silently substituted.
        buildConfigField("String", "DEPTH_MODEL_ASSET", "\"models/depth_anything_v2_small_int8.tflite\"")

        // Ownership metadata, compiled into the app so the About line and any crash report name
        // the copyright holder. All rights reserved - see LICENSE.
        buildConfigField("String", "DEVELOPER_NAME", "\"AndrexTheDev\"")
        buildConfigField("String", "DEVELOPER_EMAIL", "\"hippie.highho@gmail.com\"")
        buildConfigField("String", "PROJECT_URL", "\"https://github.com/AndrexTheDev/ConstructAR\"")

        // The licence key is a build input, not a source file (finding 240). Both fields come from
        // the same expression, so "is this the demo key" cannot disagree with "which key is in the
        // APK": the boolean is derived, never typed.
        buildConfigField("String", "LICENCE_PUBLIC_KEY", "\"$licenceKey\"")
        buildConfigField("boolean", "LICENCE_KEY_IS_DEMO", licenceKeyIsDemo.toString())

        // The label on the launcher. A beta APK that looks exactly like the sold app is a
        // support call waiting to happen, so the build type below overrides it.
        manifestPlaceholders["appLabel"] = "@string/app_name"

        // Whose entitlements this build reports. `false` everywhere except the `beta` build type,
        // which compiles it to `true`: the unlock is a build input, so a release cannot carry it
        // by accident (finding 255).
        buildConfigField("boolean", "BETA_BUILD", "false")
    }

    /**
     * Ship six languages, not the eighty that Material3 and Compose carry.
     *
     * The app's own strings exist in exactly these six locales (enforced by
     * `scripts/verify_sources.py`); every other translation in the dependency tree is dead
     * weight in the APK. Filtering here removes them before packaging, which resource
     * shrinking cannot do on its own, because locale folders of *used* resources are kept.
     */
    androidResources {
        localeFilters += listOf("en", "de", "es", "fr", "it", "pl")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    /** Robolectric needs the merged resources: the tests read real strings in six locales. */
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            /**
             * arm64 only — and that is most of the 65 MB this APK used to be.
             *
             * The package was carrying native libraries (Filament, ARCore, LiteRT, our own
             * `libconstructar_slam.so`) for two ABIs, because the emulator needs x86_64 to run
             * at all. Every device that can do ARCore is 64-bit ARM, and nobody installs a
             * measuring tool in an emulator: the second ABI is a development dependency, not a
             * shipping one. Debug builds keep every ABI, so the emulator and the CI check that
             * looks for `lib/arm64-v8a/libconstructar_slam.so` are unaffected.
             */
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }

        /**
         * The build a tester installs: every paid function unlocked, and no way to mistake it for
         * the sold one (finding 255).
         *
         * Three deliberate differences from `release`:
         *
         *  * **`BETA_BUILD = true`** - `EntitlementStore` reports the lifetime plan, so the room
         *    limit, the PDF watermark and the 3D exports are all free. It is not a licence: no key
         *    is minted, nothing is stored, and the flag lives in a build type that no release task
         *    assembles.
         *  * **`applicationIdSuffix = ".beta"` and its own label** - it installs *next to* the
         *    release build on the same phone instead of replacing it, and the icon says which one
         *    is being held.
         *  * **Debuggable, unminified, signed with the debug key** - a test report with an
         *    unreadable stack trace is a report that cannot be acted on.
         */
        create("beta") {
            applicationIdSuffix = ".beta"
            isDebuggable = true
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["appLabel"] = "ConstructAR Beta"
            buildConfigField("boolean", "BETA_BUILD", "true")
            // `:core`, `:ar-engine`, `:export` and `:native-slam` only know debug and release; the
            // beta app compiles against their debug variant instead of failing dependency
            // resolution for a "beta" variant that does not exist in the libraries.
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/*.kotlin_module",
            // Licence and index files of the dependency jars: required while linking, useless
            // in an APK that carries no jar.
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/DEPENDENCIES",
            "META-INF/INDEX.LIST",
            "META-INF/*.version",
        )
    }
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
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ar-engine"))
    implementation(project(":native-slam"))
    implementation(project(":export"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    // Tooling never reaches a release APK: the preview artifact carries the @Preview
    // annotations and the inspector wires up a debug-only view of the composition.
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Robolectric runs the storage, signature and locale tests on the JVM; `androidx.test`
    // provides `ApplicationProvider` for them.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}

/**
 * A release build carrying the demo key verifies nothing, and looks like a working build.
 *
 * `assembleRelease` depends on this check, so the failure happens before an APK exists and names
 * the environment variable to set. The one escape hatch is `-Pconstructar.sizeCheckOnly=true`,
 * which CI passes for the job that measures the release APK: that build has no signing config, is
 * never uploaded as an artifact anybody could install, and prints its own warning. A shippable
 * release passes neither.
 */
val verifyLicenceKey = tasks.register("verifyLicenceKey") {
    group = "verification"
    description = "Refuses a release build that still carries the demo licence key."
    val isDemo = licenceKeyIsDemo
    val sizeCheckOnly = providers.gradleProperty("constructar.sizeCheckOnly").isPresent
    doLast {
        if (!isDemo) {
            logger.lifecycle("verifyLicenceKey: production licence key in build")
            return@doLast
        }
        if (sizeCheckOnly) {
            logger.warn(
                "verifyLicenceKey: DEMO licence key in a size-check-only release build - this APK " +
                    "is not shippable (see docs/RELEASE_CHECKLIST.md section 2)",
            )
            return@doLast
        }
        throw GradleException(
            "release build carries the DEMO licence key, so every licence minted with the real " +
                "private key would be rejected. Set CONSTRUCTAR_PROD_PUBLIC_KEY (or " +
                "-Pconstructar.prodPublicKey=<base64url SPKI>) before building a release, or pass " +
                "-Pconstructar.sizeCheckOnly=true for a build that only measures APK size. " +
                "See docs/BILLING.md.",
        )
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyLicenceKey)
}
