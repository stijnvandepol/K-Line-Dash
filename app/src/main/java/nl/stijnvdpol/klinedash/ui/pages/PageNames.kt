package nl.stijnvdpol.klinedash.ui.pages

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.data.Page
import nl.stijnvdpol.klinedash.data.PageTemplate

/**
 * The number that tells pages of the same template apart ("Grid 2"); null for a template that
 * occurs once, which needs no number. Counted from 1 in page order.
 */
internal fun templateNumbers(pages: List<Page>): List<Int?> {
    val totals = pages.groupingBy { it.template }.eachCount()
    val seen = HashMap<PageTemplate, Int>()
    return pages.map { page ->
        val number = (seen[page.template] ?: 0) + 1
        seen[page.template] = number
        number.takeIf { totals.getValue(page.template) > 1 }
    }
}

internal fun pageTitle(templateLabel: String, number: Int?): String = if (number == null) templateLabel else "$templateLabel $number"

@StringRes
internal fun templateLabelRes(template: PageTemplate): Int = when (template) {
    PageTemplate.MAIN -> R.string.page_main
    PageTemplate.GRID -> R.string.page_grid
    PageTemplate.TRACK -> R.string.page_track
}

/** The name of every page: its template, numbered where the same template occurs more than once. */
@Composable
internal fun rememberPageTitles(pages: List<Page>): List<String> {
    val labels = PageTemplate.entries.associateWith { stringResource(templateLabelRes(it)) }
    return remember(pages, labels) {
        templateNumbers(pages).mapIndexed { index, number -> pageTitle(labels.getValue(pages[index].template), number) }
    }
}
