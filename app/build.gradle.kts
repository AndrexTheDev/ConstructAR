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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
