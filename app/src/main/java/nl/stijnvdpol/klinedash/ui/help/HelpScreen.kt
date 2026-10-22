package nl.stijnvdpol.klinedash.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.HorizontalRule
import nl.stijnvdpol.klinedash.ui.SecondaryButton
import nl.stijnvdpol.klinedash.ui.SectionTitle
import nl.stijnvdpol.klinedash.ui.SelectableRow
import nl.stijnvdpol.klinedash.ui.SetupSection
import nl.stijnvdpol.klinedash.ui.SupportedAdaptersList
import nl.stijnvdpol.klinedash.ui.TextActionButton
import nl.stijnvdpol.klinedash.ui.VerticalRule
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private val TopicColumnWidth = 280.dp
private val LinkButtonWidth = 280.dp
private const val EXPANDED_MARK = "−"
private const val COLLAPSED_MARK = "+"

/**
 * SETUP > Help: the topics as a list on the left, the chosen topic on the right. Everything is text
 * from string resources, so it reads offline. The bottom of the list runs the first-run setup again.
 *
 * @param onOpenSection opens another SETUP section a help text points to.
 * @param onRunSetupAgain starts the setup wizard from its first step.
 */
@Composable
internal fun HelpScreen(onOpenSection: (SetupSection) -> Unit, onRunSetupAgain: () -> Unit) {
    // Survives leaving SETUP for another page and coming back, like the section of the menu does.
    var topicIndex by rememberSaveable { mutableIntStateOf(0) }
    val topic = HelpTopic.entries[topicIndex]
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(TopicColumnWidth).fillMaxHeight()) {
            SectionTitle(stringResource(R.string.setup_help), Modifier.height(Spacing.sectionHeaderHeight))
            HorizontalRule()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                HelpTopic.entries.forEachIndexed { index, entry ->
                    SelectableRow(selected = entry == topic, onClick = { topicIndex = index }) {
                        Text(
                            stringResource(entry.titleRes),
                            style = KLineType.body,
                            color = if (entry == topic) KLineTheme.colors.ink else KLineTheme.colors.inkMuted,
                        )
                    }
                }
            }
            HorizontalRule()
            TextActionButton(stringResource(R.string.help_run_setup), onRunSetupAgain, Modifier.fillMaxWidth().height(Spacing.touchMin))
        }
        VerticalRule()
        TopicDetail(topic, onOpenSection, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TopicDetail(topic: HelpTopic, onOpenSection: (SetupSection) -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        SectionTitle(stringResource(topic.titleRes), Modifier.height(Spacing.sectionHeaderHeight))
        HorizontalRule()
        topic.items.forEachIndexed { index, item ->
            if (topic.isCollapsible) CollapsibleItem(topic, item, onOpenSection) else ReadingItem(item, onOpenSection)
            // The list of adapters belongs after the introduction, before the notes on clones and locked adapters.
            if (topic.showsAdapterList && index == 0) {
                SupportedAdaptersList(Modifier.padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical))
                HorizontalRule()
            }
        }
    }
}

/** A heading in capitals and its text, for topics that read from top to bottom. */
@Composable
private fun ReadingItem(item: HelpItem, onOpenSection: (SetupSection) -> Unit) {
    val colors = KLineTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.fieldPaddingVertical)) {
        item.heading?.let {
            Text(stringResource(it).uppercase(), style = KLineType.fieldLabel, color = colors.inkMuted, modifier = Modifier.padding(bottom = Spacing.grid / 2))
        }
        Text(stringResource(item.body), style = KLineType.body, color = colors.ink)
        item.link?.let { OpenSectionButton(it, onOpenSection) }
    }
    HorizontalRule()
}

/** A question that opens on a tap to show its answer; one open at a time is not enforced, so answers can be compared. */
@Composable
private fun CollapsibleItem(topic: HelpTopic, item: HelpItem, onOpenSection: (SetupSection) -> Unit) {
    val colors = KLineTheme.colors
    var isOpen by rememberSaveable(topic, item.body) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchMin)
            .clickable(role = Role.Button) { isOpen = !isOpen }
            .padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
    ) {
        Text(item.heading?.let { stringResource(it) }.orEmpty(), style = KLineType.body, color = colors.ink, modifier = Modifier.weight(1f))
        Text(if (isOpen) EXPANDED_MARK else COLLAPSED_MARK, style = KLineType.rowValue, color = colors.inkMuted)
    }
    if (isOpen) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.fieldPaddingHorizontal).padding(bottom = Spacing.fieldPaddingVertical)) {
            Text(stringResource(item.body), style = KLineType.body, color = colors.inkMuted)
            item.link?.let { OpenSectionButton(it, onOpenSection) }
        }
    }
    HorizontalRule()
}

@Composable
private fun OpenSectionButton(section: SetupSection, onOpenSection: (SetupSection) -> Unit) {
    SecondaryButton(
        stringResource(R.string.help_open, stringResource(section.labelRes)),
        { onOpenSection(section) },
        Modifier.padding(top = Spacing.fieldPaddingVertical).width(LinkButtonWidth),
    )
}
