package nl.stijnvdpol.klinedash.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.data.FIELD_SLOT_COUNT
import nl.stijnvdpol.klinedash.data.MainPage
import nl.stijnvdpol.klinedash.ui.DataField
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.LiveState
import nl.stijnvdpol.klinedash.ui.RPM_ID
import nl.stijnvdpol.klinedash.ui.RpmBar
import nl.stijnvdpol.klinedash.ui.ShiftLight
import nl.stijnvdpol.klinedash.ui.ValueLook
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.rememberIsStale
import nl.stijnvdpol.klinedash.ui.rememberReading
import nl.stijnvdpol.klinedash.ui.sweepDurationMs
import nl.stijnvdpol.klinedash.ui.theme.KLineType

private const val RPM_FIELD_WEIGHT = 2f
private const val SPEED_FIELD_WEIGHT = 1.4f
private const val COOLANT_FIELD_WEIGHT = 1f
private val SmallFieldRowHeight = 104.dp

/**
 * The big fields may grow past their design size to fill the field; the small ones only shrink.
 * RPM grows a little more than the others so it stays the one dominant number.
 */
private const val RPM_MAX_GROWTH = 1.75f
private const val PRIMARY_MAX_GROWTH = 1.6f

private val PrimaryWeights = listOf(RPM_FIELD_WEIGHT, SPEED_FIELD_WEIGHT, COOLANT_FIELD_WEIGHT)
private val PrimaryLooks = listOf(
    ValueLook(KLineType.heroValue, RPM_MAX_GROWTH),
    ValueLook(KLineType.primaryValue, PRIMARY_MAX_GROWTH),
    ValueLook(KLineType.primaryValue, PRIMARY_MAX_GROWTH),
)
private val SmallLook = ValueLook(KLineType.fieldValue)

/** The classic dashboard: the RPM bar on top, three primary fields and a row of six small ones, all separated by rules. */
@Composable
internal fun MainPageContent(page: MainPage, live: LiveState) {
    Column(Modifier.fillMaxSize()) {
        RpmBarRow(live)
        HorizontalRule()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            page.primary.forEachIndexed { slot, id ->
                if (slot > 0) VerticalRule()
                DataField(id, PrimaryLooks[slot], live, Modifier.weight(PrimaryWeights[slot]).fillMaxHeight())
            }
        }
        HorizontalRule()
        Row(Modifier.height(SmallFieldRowHeight).fillMaxWidth()) {
            for (slot in 0 until FIELD_SLOT_COUNT) {
                if (slot > 0) VerticalRule()
                DataField(page.fields[slot], SmallLook, live, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The segmented RPM bar with its scale, on Main and Track; [shiftLight] makes it flash (Track). */
@Composable
internal fun RpmBarRow(live: LiveState, shiftLight: ShiftLight? = null) {
    val rpmSignal = remember { checkNotNull(Signals.byId(RPM_ID)) }
    val rpm = rememberReading(live.readings, RPM_ID)
    val stale = rememberIsStale(rpm, live.nowMs, rpmSignal)
    val sweepMs = remember(live.stats) { derivedStateOf { sweepDurationMs(live.stats.value.signalHz[RPM_ID]) } }
    RpmBar(rpm, stale, sweepMs, Modifier.fillMaxWidth(), shiftLight)
}
