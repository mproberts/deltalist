plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Establish publishing coordinates. Actual publish wiring (maven-publish / signing /
// npm / SPM) is intentionally deferred; these just give every module a group + version.
allprojects {
    group = "com.latenighthack.deltalist"
    version = "0.1.0"
}
