package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val EmptyStateWidth = 520.dp

/**
 * What the dashboard says before any adapter is chosen: one calm invitation with the two ways on,
 * the first-run setup or the simulator. It scrolls, so a phone held in landscape still reaches both buttons.
 */
@Composable
internal fun DashEmptyState(onSetUpAdapter: () -> Unit, onTrySimulator: () -> Unit) {
    val colors = KLineTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.fieldPaddingHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.grid * 2, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.empty_dash_title),
            style = KLineType.title,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.empty_dash_body),
            style = KLineType.body,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EmptyStateWidth),
        )
        Column(Modifier.widthIn(max = EmptyStateWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
            PrimaryButton(stringResource(R.string.empty_dash_setup), onSetUpAdapter, Modifier.fillMaxWidth().height(Spacing.touchMin))
            SecondaryButton(stringResource(R.string.empty_dash_simulator), onTrySimulator, Modifier.fillMaxWidth().height(Spacing.touchMin))
        }
        Text(
            stringResource(R.string.empty_dash_simulator_hint),
            style = KLineType.caption,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EmptyStateWidth),
        )
    }
}
