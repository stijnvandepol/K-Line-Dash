package nl.stijnvdpol.klinedash.ui.help

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.stijnvdpol.klinedash.BuildConfig
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.service.CrashReport
import nl.stijnvdpol.klinedash.ui.DangerButton
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.NoticeLine
import nl.stijnvdpol.klinedash.ui.SecondaryButton
import nl.stijnvdpol.klinedash.ui.SectionTitle
import nl.stijnvdpol.klinedash.ui.TextActionButton
import nl.stijnvdpol.klinedash.ui.TwoPane
import nl.stijnvdpol.klinedash.ui.formatTripStart
import nl.stijnvdpol.klinedash.ui.graphViewModel
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val CRASH_SUMMARY_MAX_LINES = 4

/**
 * SETUP > About: what the app is and the version, the privacy statement with the reason for every
 * permission, the open-source licenses with the text of the Apache License 2.0, and the crash
 * reports with export and clear.
 */
@Composable
internal fun AboutScreen() {
    TwoPane(
        left = {
            AppBlock()
            PrivacyBlock()
        },
        right = {
            LicensesBlock()
            CrashBlock()
        },
    )
}

@Composable
private fun AppBlock() {
    val colors = KLineTheme.colors
    Text(
        stringResource(R.string.app_name).uppercase(),
        style = KLineType.fieldValue,
        color = colors.ink,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal).padding(top = Spacing.fieldPaddingHorizontal),
    )
    Text(
        stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
        style = KLineType.caption,
        color = colors.inkMuted,
        modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid / 2),
    )
    Text(
        stringResource(R.string.about_tagline),
        style = KLineType.body,
        color = colors.ink,
        modifier = Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
    HorizontalRule()
}

@Composable
private fun PrivacyBlock() {
    SectionTitle(stringResource(R.string.about_privacy_title))
    AboutText(stringResource(R.string.about_privacy_body), KLineTheme.colors.ink)
    HorizontalRule(Modifier.padding(top = Spacing.fieldPaddingVertical))
    SectionTitle(stringResource(R.string.about_permissions_title))
    PermissionLine(R.string.about_perm_bluetooth_h, R.string.about_perm_bluetooth_b)
    PermissionLine(R.string.about_perm_location_h, R.string.about_perm_location_b)
    PermissionLine(R.string.about_perm_notifications_h, R.string.about_perm_notifications_b)
    PermissionLine(R.string.about_perm_network_h, R.string.about_perm_network_b)
    PermissionLine(R.string.about_perm_options_h, R.string.about_perm_options_b)
}

@Composable
private fun PermissionLine(headingRes: Int, bodyRes: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        Text(stringResource(headingRes).uppercase(), style = KLineType.fieldLabel, color = KLineTheme.colors.inkMuted)
        Text(stringResource(bodyRes), style = KLineType.body, color = KLineTheme.colors.ink)
    }
    HorizontalRule()
}

@Composable
private fun AboutText(text: String, color: Color) {
    Text(
        text,
        style = KLineType.body,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid / 2),
    )
}

@Composable
private fun LicensesBlock() {
    val colors = KLineTheme.colors
    SectionTitle(stringResource(R.string.about_licenses_title))
    AboutText(stringResource(R.string.about_licenses_intro), colors.inkMuted)
    HorizontalRule(Modifier.padding(top = Spacing.grid))
    OpenSourceLibraries.forEach { library ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
            horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
        ) {
            Text(library.name, style = KLineType.body, color = colors.ink, modifier = Modifier.weight(1f))
            Text(library.license, style = KLineType.caption, color = colors.inkMuted)
        }
        HorizontalRule()
    }
    LicenseText()
}

/** The Apache License 2.0 in full, from a raw resource, shown on a tap: it is long and most users never open it. */
@Composable
private fun LicenseText() {
    var isShown by remember { mutableStateOf(false) }
    TextActionButton(
        stringResource(if (isShown) R.string.about_license_hide else R.string.about_license_show),
        { isShown = !isShown },
        Modifier.fillMaxWidth().height(Spacing.touchMin),
    )
    HorizontalRule()
    if (!isShown) return
    val resources = LocalContext.current.resources
    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { resources.openRawResource(R.raw.license_apache_2_0).bufferedReader().use { it.readText() } }
    }
    Text(
        text.orEmpty(),
        style = KLineType.caption.copy(fontFamily = FontFamily.Monospace),
        color = KLineTheme.colors.inkMuted,
        modifier = Modifier.padding(Spacing.fieldPaddingHorizontal),
    )
    HorizontalRule()
}

@Composable
private fun CrashBlock() {
    val vm = graphViewModel { AboutViewModel.forGraph(it) }
    val crashes by vm.crashes.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reload() }
    // Storage Access Framework: no storage permission needed.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    val colors = KLineTheme.colors
    SectionTitle(stringResource(R.string.about_crash_title))
    val reports = crashes ?: return
    val latest = reports.firstOrNull()
    if (latest == null) {
        AboutText(stringResource(R.string.about_crash_none), colors.ink)
    } else {
        CrashSummary(latest, reports.size)
    }
    AboutText(stringResource(R.string.about_crash_hint), colors.inkMuted)
    notice?.let { NoticeLine(it, colors.ink) }
    if (latest != null) {
        Row(
            Modifier.fillMaxWidth().padding(Spacing.fieldPaddingHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
        ) {
            SecondaryButton(
                stringResource(R.string.about_crash_export),
                onClick = {
                    val name = vm.exportFileName()
                    try {
                        exportLauncher.launch(name)
                    } catch (e: ActivityNotFoundException) {
                        // Many head units ship without a DocumentsUI app.
                        vm.exportToAppFolder(name)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            DangerButton(stringResource(R.string.about_crash_clear), vm::clear, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CrashSummary(latest: CrashReport, count: Int) {
    val colors = KLineTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid / 2)) {
        Text(
            stringResource(R.string.about_crash_latest, formatTripStart(latest.timeMs), latest.appVersion),
            style = KLineType.body,
            color = colors.ink,
        )
        Text(latest.summary, style = KLineType.caption, color = colors.inkMuted, maxLines = CRASH_SUMMARY_MAX_LINES)
        if (count > 1) Text(stringResource(R.string.about_crash_count, count.toString()), style = KLineType.caption, color = colors.inkMuted)
    }
}
