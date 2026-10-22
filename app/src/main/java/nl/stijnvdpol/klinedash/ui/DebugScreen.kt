package nl.stijnvdpol.klinedash.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val QuickCommands = listOf("ATZ", "ATI", "ATRV", "ATDP", "ATDPN", "0100", "010C", "03")
private const val QUICK_COMMANDS_PER_ROW = 4
private const val LOG_WEIGHT = 2f
private const val CONTROLS_WEIGHT = 1f
private val LogTimeWidth = 120.dp
private val SendButtonWidth = 120.dp

@Composable
fun DebugScreen() {
    val vm = graphViewModel { DebugViewModel(it) }
    val lines by vm.lines.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val autoScroll by vm.autoScroll.collectAsStateWithLifecycle()
    val reply by vm.reply.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val connection by vm.connectionState.collectAsStateWithLifecycle()

    // Storage Access Framework: no storage permission needed.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) vm.exportTo(uri) }

    Row(Modifier.fillMaxSize()) {
        // Left: the log with its own controls underneath. Right: the adapter, what it is and what to send it.
        Column(Modifier.weight(LOG_WEIGHT).fillMaxHeight()) {
            LogPanel(lines, autoScroll, Modifier.weight(1f).fillMaxWidth())
            notice?.let { Notice(it) }
            HorizontalRule()
            LogToolbar(
                vm = vm,
                paused = paused,
                autoScroll = autoScroll,
                canExport = link != null,
                onExport = {
                    val name = vm.exportFileName()
                    try {
                        exportLauncher.launch(name)
                    } catch (e: ActivityNotFoundException) {
                        // Many head units ship without a DocumentsUI app.
                        vm.exportToAppFolder(name, pickerMissing = true)
                    }
                },
            )
        }
        VerticalRule()
        Column(
            modifier = Modifier
                .weight(CONTROLS_WEIGHT)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        ) {
            AdapterSection((connection as? ConnectionState.Connected)?.info)
            CommandSection(sending = sending, reply = reply, onSend = vm::send)
        }
    }
}

/**
 * What the adapter reported at connect. Always shown, with "--" while not connected, so the command
 * section below never jumps. The battery voltage is left out: it is live on the dashboard.
 */
@Composable
private fun AdapterSection(info: AdapterInfo?) {
    val words = AdapterWords(
        yes = stringResource(R.string.value_yes),
        no = stringResource(R.string.value_no),
        none = stringResource(R.string.value_none),
        chipGenuine = stringResource(R.string.value_chip_genuine),
        chipStn = stringResource(R.string.value_chip_stn),
        chipClone = stringResource(R.string.value_chip_clone),
        timingAt1 = stringResource(R.string.value_timing_at1),
        timingAt2 = stringResource(R.string.value_timing_at2),
    )
    SectionTitle(stringResource(R.string.debug_section_adapter))
    HorizontalRule()
    adapterValues(info, words).forEach { (field, value) -> InfoRow(stringResource(field.labelRes), value) }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    val colors = KLineTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
    ) {
        Text(label, style = KLineType.body, color = colors.inkMuted, modifier = Modifier.weight(1f))
        Text(value ?: MISSING_VALUE, style = KLineType.body, color = if (value == null) colors.inkFaint else colors.ink)
    }
    HorizontalRule()
}

/** The quick commands as text buttons in rows of [QUICK_COMMANDS_PER_ROW], 72 dp high, separated by rules. */
@Composable
private fun QuickCommandGrid(enabled: Boolean, onCommand: (String) -> Unit) {
    HorizontalRule()
    QuickCommands.chunked(QUICK_COMMANDS_PER_ROW).forEach { row ->
        Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
            row.forEachIndexed { column, command ->
                if (column > 0) VerticalRule()
                TextActionButton(command, { onCommand(command) }, Modifier.weight(1f).fillMaxHeight(), enabled)
            }
        }
        HorizontalRule()
    }
}

/**
 * Pause/resume, clear and export as 72 dp cells along the bottom of the log, then the auto-scroll
 * switch, which gets the room of two cells for its label.
 */
@Composable
private fun LogToolbar(vm: DebugViewModel, paused: Boolean, autoScroll: Boolean, canExport: Boolean, onExport: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(Spacing.touchMin)) {
        TextActionButton(
            stringResource(if (paused) R.string.debug_resume else R.string.debug_pause),
            { vm.setPaused(!paused) },
            Modifier.weight(1f).fillMaxHeight(),
        )
        VerticalRule()
        TextActionButton(stringResource(R.string.debug_clear), vm::clear, Modifier.weight(1f).fillMaxHeight())
        VerticalRule()
        TextActionButton(stringResource(R.string.debug_export), onExport, Modifier.weight(1f).fillMaxHeight(), canExport)
        VerticalRule()
        SwitchRow(stringResource(R.string.debug_auto_scroll), autoScroll, vm::setAutoScroll, Modifier.weight(2f).fillMaxHeight())
    }
}

/** Result of an export, for a few seconds above the toolbar. */
@Composable
private fun Notice(text: String) {
    HorizontalRule()
    Text(
        text,
        style = KLineType.body,
        color = KLineTheme.colors.inkMuted,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
    )
}

@Composable
private fun LogPanel(lines: List<LogLine>, autoScroll: Boolean, modifier: Modifier) {
    if (lines.isEmpty()) {
        Text(
            stringResource(R.string.debug_log_empty),
            style = KLineType.body,
            color = KLineTheme.colors.inkMuted,
            modifier = modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
        )
    } else {
        LogList(lines, autoScroll, modifier)
    }
}

/**
 * The list is a whole number of lines high and starts [Spacing.grid] below the top: following the
 * newest message, the oldest visible line is then complete instead of cut in half at the edge.
 */
@Composable
private fun LogList(lines: List<LogLine>, autoScroll: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val lastId = lines.last().id
    // scrollToItem, not animate: animating on every batch is wasted work on a weak CPU.
    LaunchedEffect(lastId, autoScroll) {
        if (autoScroll) listState.scrollToItem(lines.lastIndex)
    }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val lineHeightPx = with(density) { KLineType.mono.lineHeight.roundToPx() }
        val roomPx = with(density) { (maxHeight - Spacing.grid * 2).roundToPx() }
        val listHeight = with(density) { (roomPx / lineHeightPx * lineHeightPx).toDp() }
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(top = Spacing.grid).fillMaxWidth().height(listHeight),
            contentPadding = PaddingValues(horizontal = Spacing.fieldPaddingHorizontal),
        ) {
            items(lines, key = { it.id }) { line -> LogRow(line) }
        }
    }
}

/** The only place that uses the monospace style: columns of hex must line up. Sent is white, received grey. */
@Composable
private fun LogRow(line: LogLine) {
    val colors = KLineTheme.colors
    val color = when (line.kind) {
        LogKind.TX -> colors.ink
        LogKind.RX -> colors.inkMuted
        LogKind.ERROR -> colors.danger
        LogKind.INFO -> colors.inkMuted
    }
    Row(Modifier.fillMaxWidth()) {
        Text(text = line.time, style = KLineType.mono, color = colors.inkMuted, modifier = Modifier.width(LogTimeWidth))
        Text(text = line.text, style = KLineType.mono, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CommandSection(sending: Boolean, reply: CommandReply?, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val send = { onSend(input) }

    SectionTitle(stringResource(R.string.debug_section_command))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal).padding(bottom = Spacing.fieldPaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.grid),
    ) {
        FormTextField(
            value = input,
            onValueChange = { input = it.uppercase() },
            modifier = Modifier.weight(1f),
            placeholder = stringResource(R.string.debug_command_hint),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        PrimaryButton(
            stringResource(R.string.debug_send),
            onClick = { send() },
            modifier = Modifier.width(SendButtonWidth),
            enabled = !sending && input.isNotBlank(),
        )
    }
    QuickCommandGrid(enabled = !sending) { command ->
        input = command
        onSend(command)
    }
    reply?.let { ReplyBlock(it) }
}

/**
 * Under the grid: command and reply type as a caps label (muted for data, amber for anything the
 * adapter answered that is not data), the reply itself in white below it.
 */
@Composable
private fun ReplyBlock(reply: CommandReply) {
    val colors = KLineTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(Spacing.grid),
    ) {
        Text(
            "${reply.command}: ${reply.type}".uppercase(),
            style = KLineType.fieldLabel,
            color = if (reply.isData) colors.inkMuted else colors.caution,
        )
        Text(reply.text, style = KLineType.body, color = colors.ink)
    }
    HorizontalRule()
}
