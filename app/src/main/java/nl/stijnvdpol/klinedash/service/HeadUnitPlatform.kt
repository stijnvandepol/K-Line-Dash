package nl.stijnvdpol.klinedash.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/** The head-unit families that need different instructions to keep the app alive. */
enum class HeadUnitPlatform { FYT, MTC, GENERIC }

/**
 * What [detectPlatform] looks at: three Build strings and which of [PROBE_PACKAGES] are installed.
 * Package names are probed one by one (and declared in the manifest's `queries`) instead of listing
 * every installed package, which would need a sensitive permission.
 */
internal data class DeviceHints(
    val hardware: String,
    val board: String,
    val manufacturer: String,
    val installedPackages: Set<String>,
)

private val FYT_PACKAGES = listOf("com.syu.ms", "com.syu.bt")
private val MTC_PACKAGES = listOf("com.microntek.bootcontrol")

/** Keep in sync with the `queries` block of AndroidManifest.xml. */
internal val PROBE_PACKAGES = FYT_PACKAGES + MTC_PACKAGES

/** Chip and vendor names seen in Build.HARDWARE / BOARD of FYT and UIS (Unisoc) head units. */
private val FYT_BUILD_HINTS = listOf("fyt", "uis7862", "uis8581", "uis7870", "ums512", "ums9620", "sc9863", "s9863")
private val MTC_BUILD_HINTS = listOf("microntek", "mtcd", "mtce")

/**
 * A best-effort guess; the user sees it on screen and the generic instructions cover the rest.
 * Installed vendor apps are stronger evidence than Build strings, so they are checked first.
 */
internal fun detectPlatform(hints: DeviceHints): HeadUnitPlatform {
    val build = listOf(hints.hardware, hints.board, hints.manufacturer).map { it.lowercase(Locale.ROOT) }
    fun matches(words: List<String>) = build.any { value -> words.any { it in value } }
    return when {
        FYT_PACKAGES.any { it in hints.installedPackages } -> HeadUnitPlatform.FYT
        MTC_PACKAGES.any { it in hints.installedPackages } -> HeadUnitPlatform.MTC
        matches(FYT_BUILD_HINTS) -> HeadUnitPlatform.FYT
        matches(MTC_BUILD_HINTS) -> HeadUnitPlatform.MTC
        else -> HeadUnitPlatform.GENERIC
    }
}

fun detectPlatform(context: Context): HeadUnitPlatform {
    val installed = PROBE_PACKAGES.filterTo(HashSet()) { isPackageInstalled(context.packageManager, it) }
    return detectPlatform(DeviceHints(Build.HARDWARE, Build.BOARD, Build.MANUFACTURER, installed))
}

private fun isPackageInstalled(packageManager: PackageManager, name: String): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(name, 0)
    }
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}
