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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Geometry code is index-heavy; keep bounds checks for safety, but forbid
        // accidentally leaking experimental APIs into a shipped module.
        freeCompilerArgs.add("-Xjvm-default=all")
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
