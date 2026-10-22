package nl.stijnvdpol.klinedash.ui.trips

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.DialogText
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.PrimaryButton
import nl.stijnvdpol.klinedash.ui.RaceDialog
import nl.stijnvdpol.klinedash.ui.SelectableRow
import nl.stijnvdpol.klinedash.ui.rememberInitialFocus
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType

private val ListMaxHeight = 360.dp

/** The saved tracks as a list; a tap chooses the track for the next lap session and closes the dialog. */
@Composable
internal fun ChooseTrackDialog(vm: SessionViewModel, onDismiss: () -> Unit) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val chosen by vm.chosenTrack.collectAsStateWithLifecycle()
    val focus = rememberInitialFocus()
    RaceDialog(
        title = stringResource(R.string.session_choose_title),
        onDismiss = onDismiss,
        actions = { PrimaryButton(stringResource(R.string.btn_cancel), onDismiss, Modifier.weight(1f).focusRequester(focus)) },
    ) {
        HorizontalRule()
        if (tracks.isEmpty()) {
            DialogText(stringResource(R.string.session_no_tracks))
        } else {
            Column(Modifier.heightIn(max = ListMaxHeight).verticalScroll(rememberScrollState())) {
                tracks.forEach { track ->
                    SelectableRow(selected = track.id == chosen?.id, onClick = {
                        vm.pickTrack(track.id)
                        onDismiss()
                    }) {
                        Text(track.name, style = KLineType.body, color = KLineTheme.colors.ink, maxLines = 1)
                    }
                }
            }
        }
    }
}
