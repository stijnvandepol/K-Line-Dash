package nl.stijnvdpol.klinedash.ui

import nl.stijnvdpol.klinedash.core.diag.DiagnosticReport
import nl.stijnvdpol.klinedash.core.diag.Dtc
import nl.stijnvdpol.klinedash.core.diag.DtcKind
import nl.stijnvdpol.klinedash.core.diag.DtcList
import nl.stijnvdpol.klinedash.core.diag.FreezeFrame
import nl.stijnvdpol.klinedash.core.diag.VehicleInfo

internal fun sampleReport(
    stored: List<Dtc> = emptyList(),
    pending: List<Dtc> = emptyList(),
    freezeFrame: FreezeFrame? = null,
    isMilOn: Boolean = stored.isNotEmpty(),
    readAtMs: Long = 1_000,
) = DiagnosticReport(
    isMilOn = isMilOn,
    reportedDtcCount = stored.size,
    isSparkIgnition = true,
    monitors = emptyList(),
    stored = DtcList(DtcKind.STORED, true, stored),
    pending = DtcList(DtcKind.PENDING, true, pending),
    permanent = DtcList(DtcKind.PERMANENT, false, emptyList()),
    freezeFrame = freezeFrame,
    vehicleInfo = VehicleInfo(vin = null, ecuName = null),
    readAtMs = readAtMs,
)
