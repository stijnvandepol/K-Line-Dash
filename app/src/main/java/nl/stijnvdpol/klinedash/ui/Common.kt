package nl.stijnvdpol.klinedash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import nl.stijnvdpol.klinedash.AppGraph
import nl.stijnvdpol.klinedash.appGraph
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.service.describe
import nl.stijnvdpol.klinedash.ui.theme.accentColor

/** Creates an Activity-scoped ViewModel that gets the manual DI [AppGraph] (no Hilt). */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.appGraph
    return viewModel(factory = viewModelFactory { initializer { create(graph) } })
}

/** Connection state as a coloured dot plus Dutch text; [large] for the dashboard. */
@Composable
fun ConnectionStatusCard(state: ConnectionState, modifier: Modifier = Modifier, large: Boolean = false) {
    val resources = LocalContext.current.resources
    val text = remember(state) { state.describe(resources) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Box(
                Modifier
                    .size(if (large) 28.dp else 20.dp)
                    .background(state.accentColor(), CircleShape),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = text,
                style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
