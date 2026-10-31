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

subprojects {
    tasks.withType(Test::class.java).configureEach {
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
