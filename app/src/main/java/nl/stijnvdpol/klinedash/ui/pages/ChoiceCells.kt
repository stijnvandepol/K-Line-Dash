package nl.stijnvdpol.klinedash.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import nl.stijnvdpol.klinedash.ui.PrimaryButton
import nl.stijnvdpol.klinedash.ui.SecondaryButton
import nl.stijnvdpol.klinedash.ui.theme.Spacing

/** One row of equal 72 dp buttons of which exactly one is chosen: the chosen one is filled, the others outlined. */
@Composable
internal fun ChoiceCells(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(Spacing.fieldPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(Spacing.fieldPaddingHorizontal),
    ) {
        labels.forEachIndexed { index, label ->
            val cell = Modifier.weight(1f)
            if (index == selectedIndex) PrimaryButton(label, { onSelect(index) }, cell) else SecondaryButton(label, { onSelect(index) }, cell)
        }
    }
}
