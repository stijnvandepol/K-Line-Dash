package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.core.alert.AlertSeverity
import nl.stijnvdpol.klinedash.ui.theme.KLineColors
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing
import nl.stijnvdpol.klinedash.ui.theme.readableOn

private val FrameThickness = 6.dp
private val BannerHeight = 56.dp

private fun KLineColors.fillFor(severity: AlertSeverity): Color = when (severity) {
    AlertSeverity.CAUTION -> caution
    AlertSeverity.DANGER -> danger
}

/**
 * The screen with the warnings frame: a [FrameThickness] frame in the severity colour around
 * everything while [severity] is not null. The content insets by the same amount only then, so the
 * frame never covers anything and a page is at full size when nothing is wrong.
 */
@Composable
internal fun WarningFrame(severity: AlertSeverity?, content: @Composable ColumnScope.() -> Unit) {
    val colors = KLineTheme.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(if (severity == null) 0.dp else FrameThickness), content = content)
        if (severity != null) Box(Modifier.fillMaxSize().border(FrameThickness, colors.fillFor(severity)))
    }
}

/**
 * Names the leading warning on a full-width bar of its severity colour, background-coloured text on
 * amber and ink-coloured on red (swapped when the theme makes that hard to read), with "+N" for the others. A tap hides it (the frame stays). It never blinks.
 */
@Composable
internal fun WarningBanner(banner: Banner, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = KLineTheme.colors
    val fill = colors.fillFor(banner.alert.severity)
    val textColor = if (banner.alert.severity == AlertSeverity.DANGER) {
        readableOn(fill, preferred = colors.ink, alternative = colors.background)
    } else {
        readableOn(fill, preferred = colors.background, alternative = colors.ink)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(BannerHeight)
            .background(fill)
            .clickable(role = Role.Button, onClick = onDismiss)
            .padding(horizontal = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(bannerText(banner.alert, LocalUnitSystem.current), style = KLineType.fieldLabel, color = textColor, maxLines = 1, modifier = Modifier.weight(1f))
        if (banner.moreCount > 0) Text("+${banner.moreCount}", style = KLineType.fieldLabel, color = textColor, maxLines = 1)
    }
}
