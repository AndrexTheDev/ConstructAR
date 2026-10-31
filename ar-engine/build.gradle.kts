plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.constructar.arengine"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    api(project(":core"))
    api(project(":native-slam"))

    // ARCore + SceneView (Filament). `arsceneview` brings `sceneview` and Filament with it;
    // see docs/SETUP.md for why the Android artifact is used instead of `sceneview-compose`.
    api(libs.arcore)
    api(libs.arsceneview)
    // Filament is used directly (EntityManager / RenderableManager / TransformManager) for all
    // custom geometry; see SceneMeshRenderer. Pinned here so the renderer never depends on the
    // Filament version SceneView happens to pull in.
    implementation(libs.filament.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Compose (UI for the in-scene HUD labels and the AR permission/coaching surfaces)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // On-device monocular depth (Depth Anything V2 Small, Apache-2.0) via LiteRT.
    // Optional at runtime: enabled only when a model file is present.
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
