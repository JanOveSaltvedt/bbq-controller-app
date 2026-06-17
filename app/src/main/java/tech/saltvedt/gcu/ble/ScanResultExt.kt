package tech.saltvedt.gcu.ble

import no.nordicsemi.android.kotlin.ble.core.scanner.BleScanResult
import java.util.UUID

/** Service UUIDs advertised in a scan result, or empty if none were present. */
fun BleScanResult.serviceUuids(): List<UUID> =
    data?.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

/** Latest signal strength of this advertisement in dBm, or null when unavailable. */
fun BleScanResult.rssi(): Int? = data?.rssi

/**
 * Manufacturer-specific data fields as (companyId, bytes) pairs. The Nordic library
 * exposes these as a SparseArray; flatten it so callers can iterate without Android
 * collection types leaking into the pure decoders.
 */
fun BleScanResult.manufacturerData(): List<Pair<Int, ByteArray>> {
    val mfg = data?.scanRecord?.manufacturerSpecificData ?: return emptyList()
    return (0 until mfg.size()).map { i -> mfg.keyAt(i) to mfg.valueAt(i).value }
}
