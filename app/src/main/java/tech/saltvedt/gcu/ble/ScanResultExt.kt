package tech.saltvedt.gcu.ble

import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResult
import java.util.UUID

/** Service UUIDs advertised in a scan result, or empty if none were present. */
fun BleScanResult.serviceUuids(): List<UUID> =
    data?.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
