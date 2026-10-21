package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading
import nl.stijnvdpol.klinedash.core.obd.Signals
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val DEMAND_OWNER = "dashboard"
private const val SMALL_FIELD_COUNT = 6
private const val RPM_FIELD_WEIGHT = 2f
private const val SPEED_FIELD_WEIGHT = 1.4f
private const val COOLANT_FIELD_WEIGHT = 1f
private val SmallFieldRowHeight = 104.dp

/**
 * The big fields may grow past their design size to fill the field; the small ones only shrink.
 * RPM grows a little more than speed and water so it stays the one dominant number.
 */
private const val RPM_MAX_GROWTH = 1.75f
private const val PRIMARY_MAX_GROWTH = 1.6f
private val LabelToValueGap = 8.dp

/**
 * Dashboard: the RPM bar on top, three primary fields (RPM, speed, coolant) and a row of six small
 * ones, all separated by rules. Asks the poller for exactly what it shows, while visible.
 */
@Composable
fun DashboardScreen() {
    val vm = graphViewModel { LiveDataViewModel() }
    val poller by vm.poller.collectAsStateWithLifecycle()
    val supportedPids by vm.supportedPids.collectAsStateWithLifecycle()
    val fieldIds = remember(supportedPids) { dashboardFieldIds(supportedPids) }
    DemandWhileVisible(poller, DEMAND_OWNER, remember(fieldIds) { dashboardDemandIds(fieldIds) })

    // States, not values: only the small composables below read them, so this one never recomposes
    // for a new reading.
    val readings = vm.readings.collectAsStateWithLifecycle()
    val stats = vm.stats.collectAsStateWithLifecycle()
    val nowMs = rememberNowMs()
    val engineRunning = rememberEngineRunning(readings)

    Column(Modifier.fillMaxSize()) {
        RpmBarRow(readings, stats, nowMs)
        HorizontalRule()
        PrimaryRow(readings, nowMs, engineRunning, Modifier.weight(1f).fillMaxWidth())
        HorizontalRule()
        SmallFieldRow(fieldIds, readings, nowMs, engineRunning, Modifier.height(SmallFieldRowHeight).fillMaxWidth())
    }
}

@Composable
private fun RpmBarRow(readings: State<Map<String, Reading>>, stats: State<PollStats>, nowMs: State<Long>) {
    val rpmSignal = remember { checkNotNull(Signals.byId(RPM_ID)) }
    val rpm = rememberReading(readings, RPM_ID)
    val stale = rememberIsStale(rpm, nowMs, rpmSignal)
    val sweepMs = remember(stats) { derivedStateOf { sweepDurationMs(stats.value.signalHz[RPM_ID]) } }
    RpmBar(rpm, stale, sweepMs, Modifier.fillMaxWidth())
}

@Composable
private fun PrimaryRow(
    readings: State<Map<String, Reading>>,
    nowMs: State<Long>,
    engineRunning: State<Boolean>,
    modifier: Modifier,
) {
    Row(modifier) {
        DataField(RPM_ID, KLineType.heroValue, RPM_MAX_GROWTH, readings, nowMs, engineRunning, Modifier.weight(RPM_FIELD_WEIGHT).fillMaxHeight())
        VerticalRule()
        DataField(SPEED_ID, KLineType.primaryValue, PRIMARY_MAX_GROWTH, readings, nowMs, engineRunning, Modifier.weight(SPEED_FIELD_WEIGHT).fillMaxHeight())
        VerticalRule()
        DataField(COOLANT_ID, KLineType.primaryValue, PRIMARY_MAX_GROWTH, readings, nowMs, engineRunning, Modifier.weight(COOLANT_FIELD_WEIGHT).fillMaxHeight())
    }
}

@Composable
private fun SmallFieldRow(
    fieldIds: List<String>,
    readings: State<Map<String, Reading>>,
    nowMs: State<Long>,
    engineRunning: State<Boolean>,
    modifier: Modifier,
) {
    Row(modifier) {
        for (index in 0 until SMALL_FIELD_COUNT) {
            if (index > 0) VerticalRule()
            val id = fieldIds.getOrNull(index)
            if (id == null) {
                Spacer(Modifier.weight(1f))
            } else {
                DataField(id, KLineType.fieldValue, 1f, readings, nowMs, engineRunning, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** The labels of the short race-display vocabulary; signals without one use their short name. */
private val FieldLabelRes = mapOf(
    RPM_ID to R.string.field_rpm,
    SPEED_ID to R.string.field_speed,
    COOLANT_ID to R.string.field_coolant,
    INTAKE_ID to R.string.field_intake,
    THROTTLE_ID to R.string.field_throttle,
    LOAD_ID to R.string.field_load,
    TIMING_ID to R.string.field_timing,
    BATTERY_ID to R.string.field_battery,
    MAF_ID to R.string.field_maf,
)

/** Label or unit of a field: muted capitals, boxed to their cap height so they sit 12 dp from the rule. */
@Composable
internal fun FieldCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = KLineType.fieldLabel,
        color = KLineTheme.colors.inkMuted,
        maxLines = 1,
        modifier = modifier.capHeightBox(KLineType.fieldLabel.fontSize),
    )
}

/**
 * One field: label top-left, unit top-right, the value right-aligned at the bottom in a slot of
 * fixed width. The value shrinks with the field (never wraps), so the layout also holds on a
 * head unit narrower than 1280 dp; [maxGrowth] above 1 lets it grow to fill a big field.
 */
@Composable
private fun DataField(
    id: String,
    valueStyle: TextStyle,
    maxGrowth: Float,
    readings: State<Map<String, Reading>>,
    nowMs: State<Long>,
    engineRunning: State<Boolean>,
    modifier: Modifier,
) {
    val signal = remember(id) { checkNotNull(Signals.byId(id)) }
    val reading = rememberReading(readings, id)
    val stale = rememberIsStale(reading, nowMs, signal)
    val tone = rememberValueTone(signal, reading, engineRunning)
    val label = FieldLabelRes[id]?.let { stringResource(it) } ?: signal.shortName
    val noReading = stringResource(R.string.field_no_reading)
    val words = rememberOnOffWords()
    BoxWithConstraints(
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = reading.value?.let { "${signal.name}: ${formatReading(signal, it.value, words)} ${signal.unit}" }
                    ?: "${signal.name}: $noReading"
            }
            .padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    ) {
        val labelHeight = with(LocalDensity.current) { KLineType.fieldLabel.fontSize.toDp() } * CAP_HEIGHT_EM
        val style = rememberFittedStyle(valueStyle, slotSample(signal), maxWidth, maxHeight - labelHeight - LabelToValueGap, maxGrowth)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                FieldCaption(label.uppercase())
                Spacer(Modifier.weight(1f))
                if (!labelIsUnit(id)) FieldCaption(signal.unit, Modifier.padding(start = Spacing.grid))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
                ValueText(signal, reading, stale, tone, style)
            }
        }
    }
}
