package nl.stijnvdpol.klinedash.core.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.stijnvdpol.klinedash.core.elm.AdapterInfo
import nl.stijnvdpol.klinedash.core.elm.ConnectionState
import nl.stijnvdpol.klinedash.core.elm.ElmLink
import nl.stijnvdpol.klinedash.core.elm.ElmResult
import nl.stijnvdpol.klinedash.core.log.CommLog
import nl.stijnvdpol.klinedash.core.log.RingCommLog
import nl.stijnvdpol.klinedash.core.obd.ObdPoller
import nl.stijnvdpol.klinedash.core.obd.PollStats
import nl.stijnvdpol.klinedash.core.obd.Reading

/** Reply lines as the connection manager hands them over: hex without spaces. */
fun data(vararg lines: String): ElmResult.Data = ElmResult.Data(lines.toList(), lines.joinToString("\r"))

val noData = ElmResult.NoData("NO DATA")

/** An [ElmLink] that answers from [responder] and remembers what was sent. */
class ScriptedLink(
    protocolNumber: String = "3",
    private val responder: (String) -> ElmResult,
) : ElmLink {
    private val connected = ConnectionState.Connected(
        AdapterInfo("ELM327 v1.5", protocolNumber, "test", 12.4, false, emptySet()),
    )
    override val state: StateFlow<ConnectionState> = MutableStateFlow(connected)
    override val log: CommLog = RingCommLog()
    val sent = ArrayList<String>()

    override fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun forgetSavedProtocol() = Unit

    override suspend fun execute(command: String, timeoutMs: Long): ElmResult {
        sent += command
        return responder(command)
    }
}

/** Looks the command up in [replies]; everything else is NO DATA. */
fun answering(replies: Map<String, ElmResult>): (String) -> ElmResult = { replies[it] ?: noData }

/** A poller that only grants the link, for tests that do not need polling. */
class PassThroughPoller(private val link: ElmLink) : ObdPoller {
    override val readings: StateFlow<Map<String, Reading>> = MutableStateFlow(emptyMap())
    override val supportedPids: StateFlow<Set<Int>?> = MutableStateFlow(null)
    override val stats: StateFlow<PollStats> = MutableStateFlow(PollStats())

    override fun demand(owner: String, signalIds: Set<String>) = Unit
    override fun release(owner: String) = Unit
    override fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun <T> withPollingPaused(block: suspend (ElmLink) -> T): T = block(link)
}
