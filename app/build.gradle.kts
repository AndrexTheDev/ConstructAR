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
