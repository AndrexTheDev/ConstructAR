plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.constructar.nativebridge"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // C++20, no exceptions/RTTI and -O3 are set in CMakeLists.txt so the host
                // test build and the NDK build compile identical semantics.
                cppFlags += listOf("-std=c++20")
                arguments += listOf("-DANDROID_STL=c++_shared")

                val rtabmapDir = providers.gradleProperty("constructar.rtabmap.dir").orNull
                if (!rtabmapDir.isNullOrBlank()) {
                    arguments += "-DCONSTRUCTAR_WITH_RTABMAP=ON"
                    arguments += "-DRTABMAP_SOURCE_DIR=$rtabmapDir"
                } else {
                    arguments += "-DCONSTRUCTAR_WITH_RTABMAP=OFF"
                }
            }
        }

        ndk {
            // 64-bit only: ARCore depth + NNAPI devices are all arm64/x86_64, and shipping
            // 32-bit halves the download for no user benefit.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Keep the scanned-volume mesher symbols: native crashes must be symbolisable.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // Required by Play for 16 KB page-size devices.
            keepDebugSymbols += "**/*.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
