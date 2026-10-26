import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The one place the version lives. versionCode follows from it (major * 10000 + minor * 100 + patch),
// so a release is a one-line change here. Both reach the app as BuildConfig.VERSION_NAME / VERSION_CODE.
val versionMajor = 1
val versionMinor = 3
val versionPatch = 0
val appVersionName = "$versionMajor.$versionMinor.$versionPatch"
val appVersionCode = versionMajor * 10_000 + versionMinor * 100 + versionPatch

// Read by the release workflow to check that the git tag matches the version.
tasks.register("printVersionName") {
    val versionName = appVersionName
    doLast { println(versionName) }
}

// Release signing: environment variables (CI) win over keystore.properties in the repo root (local).
// Both are optional so a local `assembleRelease` still works, signed with the debug key and a warning.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(environmentName: String, propertyName: String): String? =
    System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("KLD_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("KLD_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("KLD_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("KLD_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "nl.stijnvdpol.klinedash"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.stijnvdpol.klinedash"
        minSdk = 26
        // 34, not 35: avoids forced edge-to-edge on head units with odd system bars.
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The orchestrator clears the app's data before every instrumented test: see docs/RELEASING.md.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // No dependency metadata blob in the APK: it is only read by Google Play and breaks reproducible builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The ViewModel tests run on the JVM, where android.util.Log has no implementation.
    testOptions {
        unitTests.isReturnDefaultValues = true
        // One process and one cleared data directory per instrumented test; the app keeps its state in process-wide singletons.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// A debug-signed release APK cannot update one signed with the real key (and the other way round),
// so say it loudly instead of letting it ship by accident. The release workflow turns this into an error.
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.path == ":app:packageRelease" }
    if (!buildsRelease || hasReleaseSigning) return@whenReady
    val message = "Release APK is signed with the DEBUG key: no keystore.properties and no KLD_KEYSTORE_* variables " +
        "found. Fine for local testing, never publish it. See docs/RELEASING.md."
    if (System.getenv("KLD_REQUIRE_RELEASE_SIGNING") == "true") throw GradleException(message)
    logger.quiet("WARNING: $message")
}

dependencies {
    implementation(project(":obd-core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestUtil(libs.androidx.test.orchestrator)
}
