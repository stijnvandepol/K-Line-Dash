package nl.stijnvdpol.klinedash.ui.help

/** The licence a library is under, as the name the About page shows. */
internal const val APACHE_2_0 = "Apache License 2.0"

/**
 * An open-source library shipped inside the app. [groups] are the Maven groups of its modules in
 * gradle/libs.versions.toml; a test checks that every library the app ships belongs to one entry.
 */
internal class OpenSourceLibrary(val name: String, val license: String, val groups: List<String>)

/**
 * What SETUP > About lists under "Open-source licenses". The Kotlin standard library comes with the
 * Kotlin plugin rather than the version catalog. Libraries used only by tests or the build (JUnit,
 * org.json, the Room compiler, Compose tooling) are not part of the app and are not listed.
 */
internal val OpenSourceLibraries = listOf(
    OpenSourceLibrary("Kotlin and the Kotlin standard library", APACHE_2_0, listOf("org.jetbrains.kotlin")),
    OpenSourceLibrary("kotlinx.coroutines", APACHE_2_0, listOf("org.jetbrains.kotlinx")),
    OpenSourceLibrary("AndroidX Core KTX", APACHE_2_0, listOf("androidx.core")),
    OpenSourceLibrary("AndroidX Activity Compose", APACHE_2_0, listOf("androidx.activity")),
    OpenSourceLibrary("AndroidX Lifecycle", APACHE_2_0, listOf("androidx.lifecycle")),
    OpenSourceLibrary("Jetpack Compose (UI, Foundation, Material 3)", APACHE_2_0, listOf("androidx.compose.ui", "androidx.compose.foundation", "androidx.compose.material3")),
    OpenSourceLibrary("AndroidX Room", APACHE_2_0, listOf("androidx.room")),
)
