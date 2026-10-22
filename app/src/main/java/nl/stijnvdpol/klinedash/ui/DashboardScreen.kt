package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import nl.stijnvdpol.klinedash.data.DashboardLayout
import nl.stijnvdpol.klinedash.data.GridPage
import nl.stijnvdpol.klinedash.data.MainPage
import nl.stijnvdpol.klinedash.data.TrackPage
import nl.stijnvdpol.klinedash.data.shiftRpm
import nl.stijnvdpol.klinedash.ui.pages.GridPageContent
import nl.stijnvdpol.klinedash.ui.pages.MainPageContent
import nl.stijnvdpol.klinedash.ui.pages.TrackPageContent
import nl.stijnvdpol.klinedash.ui.pages.rememberPageTitles
import nl.stijnvdpol.klinedash.ui.theme.KLineTheme
import nl.stijnvdpol.klinedash.ui.theme.KLineType
import nl.stijnvdpol.klinedash.ui.theme.LocalClusterLook
import nl.stijnvdpol.klinedash.ui.theme.Spacing

private const val DEMAND_OWNER = "dashboard"

/** How long the page name stays on screen after a swipe. */
private const val PAGE_NAME_MS = 1_000L

/**
 * The dashboard: the pages of the layout side by side, swiped horizontally. Asks the poller for
 * exactly what the current page shows, while the dashboard is visible, and names the page for a
 * second after each swipe.
 */
@Composable
fun DashboardScreen() {
    val live = graphViewModel { LiveDataViewModel.forGraph(it) }
    val dashboard = graphViewModel { DashboardViewModel.forGraph(it) }
    val layout by dashboard.layout.collectAsStateWithLifecycle()
    layout?.let { DashboardPager(it, live, dashboard) }
}

@Composable
private fun DashboardPager(layout: DashboardLayout, live: LiveDataViewModel, dashboard: DashboardViewModel) {
    val pages = layout.pages
    val pagerState = rememberPagerState(initialPage = dashboard.currentPage.coerceIn(pages.indices)) { pages.size }
    LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.collect { dashboard.currentPage = it } }

    val poller by live.poller.collectAsStateWithLifecycle()
    val supportedPids by live.supportedPids.collectAsStateWithLifecycle()
    val currentPage = pages[pagerState.currentPage.coerceIn(pages.indices)]
    val cluster = LocalClusterLook.current.style
    DemandWhileVisible(poller, DEMAND_OWNER, remember(currentPage, supportedPids, cluster) { pageDemandIds(currentPage, supportedPids, cluster) })
    val shiftRpm = remember(layout) { layout.shiftRpm() }

    // States, not values: only the small composables below read them, so this one never recomposes
    // for a new reading.
    val readings = live.readings.collectAsStateWithLifecycle()
    val stats = live.stats.collectAsStateWithLifecycle()
    val nowMs = rememberNowMs()
    val toneContext = rememberToneContext(readings, live.limits.collectAsStateWithLifecycle())
    val liveState = remember(readings, stats, nowMs, toneContext) { LiveState(readings, stats, nowMs, toneContext) }
    val peaks = dashboard.peaks.collectAsStateWithLifecycle()
    val graphRevision = dashboard.graphRevision.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pagerState, Modifier.fillMaxSize()) { index ->
            when (val page = pages[index]) {
                is MainPage -> MainPageContent(page, liveState, shiftRpm)
                is GridPage -> GridPageContent(page, liveState, dashboard.history, graphRevision)
                is TrackPage -> TrackPageContent(page, liveState, peaks, dashboard::resetPeaks)
            }
        }
        PageNameTag(pagerState, rememberPageTitles(pages), Modifier.align(Alignment.TopCenter))
    }
}

/** The page name appears when a swipe has settled and goes away after [PAGE_NAME_MS]. */
@Composable
private fun PageNameTag(pagerState: PagerState, titles: List<String>, modifier: Modifier) {
    var shown by remember { mutableStateOf<String?>(null) }
    val currentTitles by rememberUpdatedState(titles)
    LaunchedEffect(pagerState) {
        // The first settled page is where the dashboard opened, not a swipe.
        snapshotFlow { pagerState.settledPage }.drop(1).collectLatest { index ->
            shown = currentTitles.getOrNull(index)
            delay(PAGE_NAME_MS)
            shown = null
        }
    }
    val name = shown ?: return
    val colors = KLineTheme.colors
    Text(
        name.uppercase(),
        style = KLineType.fieldLabel,
        color = colors.ink,
        maxLines = 1,
        modifier = modifier
            .background(colors.background)
            .border(Spacing.ruleThickness, colors.inkFaint)
            .padding(horizontal = Spacing.fieldPaddingHorizontal, vertical = Spacing.grid),
    )
}
