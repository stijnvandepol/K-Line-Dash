import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: transport contracts, ELM327 layer, OBD layer and simulator.
// No Android dependencies, so the parser tests run on any JDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
