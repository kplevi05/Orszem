import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is configured only when the owner supplies it, through a gitignored
// keystore.properties or through environment variables. No keystore, password or alias
// is ever committed, and CI never needs any of them because CI builds debug only.
// See docs/deployment/ANDROID_SIGNING.md.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "ORSZEM_RELEASE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "ORSZEM_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ORSZEM_RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ORSZEM_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
).all { !it.isNullOrBlank() }

// The base URL is origin-only with a trailing slash; path segments live in the client.
fun apiBaseUrl(default: String): String =
    providers.gradleProperty("ORSZEM_API_BASE_URL").getOrElse(default)

android {
    namespace = "hu.orszembejelento.service"
    compileSdk = 37

    defaultConfig {
        applicationId = "hu.orszembejelento.service"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "2.0.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Android emulator loopback to the developer machine.
            buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl("http://10.0.2.2:8080/")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${apiBaseUrl("https://api.orszembejelento.hu/")}\"",
            )
            // Left unsigned when no signing material is supplied, rather than silently
            // falling back to the debug key as Demo v1 did.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }

    testOptions {
        unitTests {
            // OkHttp touches android.util.Log, which is a throwing stub in unit tests.
            // Returning defaults lets the real client run on the JVM unchanged.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Conventional, small networking stack. No custom framework.
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented tests: the real Android Keystore only exists on a device or emulator,
    // so the crypto that protects the refresh token cannot be covered by a JVM test.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
