plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

subprojects {
    // AGP's library annotation extraction fails on this Windows toolchain
    // (java.io.IOException while resolving its inputs) and the AARs are not
    // published anywhere, so it is not needed for the Demo v1 build.
    tasks.matching { it.name.matches(Regex("extract.*Annotations")) }
        .configureEach { enabled = false }
}
