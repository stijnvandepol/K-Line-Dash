package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** From this width a page shows its two halves side by side; below it they stack. A 1280 dp unit and a phone in landscape both qualify. */
private val TwoPaneMinWidth = 840.dp

/**
 * The room of a page of text: [left] and [right] side by side, each scrolling on its own, or
 * stacked in one scrolling column on a narrow screen. A rule runs between them.
 */
@Composable
internal fun TwoPane(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneMinWidth) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), content = left)
                VerticalRule()
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), content = right)
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                left()
                HorizontalRule()
                right()
            }
        }
    }
}

/** One scrolling column for the pages that have a single thing to say. */
@Composable
internal fun SinglePane(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = content)
}
