package nl.stijnvdpol.klinedash.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.log.LogKind
import nl.stijnvdpol.klinedash.service.formatVoltage
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val QuickCommands = listOf("ATZ", "ATI", "ATRV", "ATDP", "ATDPN", "0100", "010C", "03")
private const val QUICK_COMMANDS_PER_ROW = 4
private const val LOG_WEIGHT = 0.58f
private const val CONTROLS_WEIGHT = 0.42f
private val LogTimeWidth = 120.dp

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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gutter),
    ) {
        LogPanel(
            lines = lines,
            autoScroll = autoScroll,
            modifier = Modifier
                .weight(LOG_WEIGHT)
                .fillMaxHeight(),
        )

        Column(
            modifier = Modifier
                .weight(CONTROLS_WEIGHT)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.gutter),
        ) {
            CommandPanel(sending = sending, reply = reply, onSend = vm::send)
            LogControls(
                vm = vm,
                paused = paused,
                autoScroll = autoScroll,
                canExport = link != null,
                notice = notice,
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
            (connection as? ConnectionState.Connected)?.let { AdapterPanel(it.info) }
        }
    }
}

@Composable
private fun AdapterPanel(info: AdapterInfo) {
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
            SectionTitle(stringResource(R.string.debug_section_adapter))
            InfoRow(stringResource(R.string.label_elm_version), info.elmVersion)
            InfoRow(stringResource(R.string.label_protocol), "${info.protocolName} (${info.protocolNumber})")
            InfoRow(
                stringResource(R.string.label_voltage),
                info.voltage?.let { "${formatVoltage(it)} V" } ?: stringResource(R.string.value_unknown),
            )
            InfoRow(
                stringResource(R.string.label_saved_protocol),
                stringResource(if (info.usedSavedProtocol) R.string.value_yes else R.string.value_no),
            )
            InfoRow(
                stringResource(R.string.label_unsupported),
                info.unsupportedCommands.sorted().joinToString().ifEmpty { stringResource(R.string.value_none) },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = KLineTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.gutter)) {
        Text(label, style = KLineType.body, color = colors.inkMuted, modifier = Modifier.weight(1f))
        Text(value, style = KLineType.body, color = colors.ink)
    }
}

/** Pause/resume, clear, auto-scroll and export for the log panel. */
@Composable
private fun LogControls(
    vm: DebugViewModel,
    paused: Boolean,
    autoScroll: Boolean,
    canExport: Boolean,
    notice: String?,
    onExport: () -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
            SectionTitle(stringResource(R.string.debug_section_log))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.grid)) {
                SecondaryButton(
                    stringResource(if (paused) R.string.debug_resume else R.string.debug_pause),
                    onClick = { vm.setPaused(!paused) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(stringResource(R.string.debug_clear), vm::clear, Modifier.weight(1f))
            }
            SwitchRow(stringResource(R.string.debug_auto_scroll), autoScroll, vm::setAutoScroll, horizontalInset = 0.dp)
            SecondaryButton(stringResource(R.string.debug_export), onExport, Modifier.fillMaxWidth(), enabled = canExport)
            notice?.let { Text(it, style = KLineType.body, color = KLineTheme.colors.inkMuted) }
        }
    }
}

@Composable
private fun LogPanel(lines: List<LogLine>, autoScroll: Boolean, modifier: Modifier) {
    Panel(modifier, padding = 0.dp) {
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.debug_log_empty),
                style = KLineType.body,
                color = KLineTheme.colors.inkMuted,
                modifier = Modifier.padding(Spacing.panelPadding),
            )
        } else {
            LogList(lines, autoScroll)
        }
    }
}

@Composable
private fun LogList(lines: List<LogLine>, autoScroll: Boolean) {
    val listState = rememberLazyListState()
    val lastId = lines.last().id
    // scrollToItem, not animate: animating on every batch is wasted work on a weak CPU.
    LaunchedEffect(lastId, autoScroll) {
        if (autoScroll) listState.scrollToItem(lines.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.panelPadding, vertical = Spacing.grid),
    ) {
        items(lines, key = { it.id }) { line -> LogRow(line) }
    }
}

/** The only place that uses the monospace style: columns of hex must line up. */
@Composable
private fun LogRow(line: LogLine) {
    val colors = KLineTheme.colors
    val color = when (line.kind) {
        LogKind.TX -> colors.inkMuted
        LogKind.RX -> colors.ink
        LogKind.ERROR -> colors.danger
        LogKind.INFO -> colors.inkMuted
    }
    Row(Modifier.fillMaxWidth()) {
        Text(text = line.time, style = KLineType.mono, color = colors.inkMuted, modifier = Modifier.width(LogTimeWidth))
        Text(text = line.text, style = KLineType.mono, color = color, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandPanel(sending: Boolean, reply: CommandReply?, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val send = { onSend(input) }

    Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.grid)) {
            SectionTitle(stringResource(R.string.debug_section_command))
            Row(
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
                    enabled = !sending && input.isNotBlank(),
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.grid),
                verticalArrangement = Arrangement.spacedBy(Spacing.grid),
                maxItemsInEachRow = QUICK_COMMANDS_PER_ROW,
            ) {
                QuickCommands.forEach { command ->
                    SecondaryButton(
                        command,
                        onClick = {
                            input = command
                            onSend(command)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !sending,
                        horizontalPadding = Spacing.grid,
                    )
                }
            }

            reply?.let { ReplyCard(it) }
        }
    }
}

@Composable
private fun ReplyCard(reply: CommandReply) {
    val colors = KLineTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.panelRaised, RoundedCornerShape(Spacing.controlRadius))
            .padding(Spacing.panelPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.grid / 2),
    ) {
        Text(
            "${reply.command}: ${reply.type}",
            style = KLineType.body,
            color = if (reply.isData) colors.ok else colors.caution,
        )
        Text(reply.text, style = KLineType.body, color = colors.ink)
    }
}
