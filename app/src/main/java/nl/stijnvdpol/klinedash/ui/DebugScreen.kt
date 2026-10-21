package nl.stijnvdpol.klinedash.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.core.log.LogKind

private val QuickCommands = listOf("ATZ", "ATI", "ATRV", "ATDP", "ATDPN", "0100", "010C", "03")
private val MinTouch = 56.dp

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

    // Storage Access Framework: no storage permission needed.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> if (uri != null) vm.exportTo(uri) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LogPanel(
            lines = lines,
            autoScroll = autoScroll,
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight(),
        )

        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LogControls(
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
            notice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }

            CommandPanel(sending = sending, reply = reply, onSend = vm::send)
        }
    }
}

/** Pause/resume, clear, auto-scroll and export for the log panel. */
@Composable
private fun LogControls(
    vm: DebugViewModel,
    paused: Boolean,
    autoScroll: Boolean,
    canExport: Boolean,
    onExport: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { vm.setPaused(!paused) },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MinTouch),
        ) { Text(stringResource(if (paused) R.string.debug_resume else R.string.debug_pause)) }
        OutlinedButton(
            onClick = vm::clear,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.debug_clear)) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MinTouch)
                .toggleable(value = autoScroll, role = Role.Switch, onValueChange = vm::setAutoScroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = autoScroll, onCheckedChange = null)
            Text(stringResource(R.string.debug_auto_scroll), style = MaterialTheme.typography.bodyLarge)
        }
        OutlinedButton(
            onClick = onExport,
            enabled = canExport,
            modifier = Modifier.heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.debug_export)) }
    }
}

@Composable
private fun LogPanel(lines: List<LogLine>, autoScroll: Boolean, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (lines.isEmpty()) {
            Text(
                stringResource(R.string.debug_log_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
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
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(lines, key = { it.id }) { line -> LogRow(line) }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val scheme = MaterialTheme.colorScheme
    val color = when (line.kind) {
        LogKind.TX -> scheme.primary
        LogKind.RX -> scheme.onSurface
        LogKind.ERROR -> scheme.error
        LogKind.INFO -> scheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = line.time,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = scheme.outline,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = line.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandPanel(sending: Boolean, reply: CommandReply?, onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val send = { onSend(input) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.uppercase() },
            label = { Text(stringResource(R.string.debug_command)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { send() },
            enabled = !sending && input.isNotBlank(),
            modifier = Modifier.heightIn(min = MinTouch),
        ) { Text(stringResource(R.string.debug_send)) }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickCommands.forEach { command ->
            FilledTonalButton(
                onClick = {
                    input = command
                    onSend(command)
                },
                enabled = !sending,
                modifier = Modifier.heightIn(min = MinTouch),
            ) { Text(command, fontFamily = FontFamily.Monospace) }
        }
    }

    reply?.let { ReplyCard(it) }
}

@Composable
private fun ReplyCard(reply: CommandReply) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${reply.command}  →  ${reply.type}",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = if (reply.isData) scheme.primary else scheme.secondary,
            )
            Text(
                reply.text,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
