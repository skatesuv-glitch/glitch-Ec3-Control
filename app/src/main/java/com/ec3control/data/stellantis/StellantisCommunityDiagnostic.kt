package com.ec3control.data.stellantis

/**
 * Experimental Plan B connector.
 *
 * Kept isolated from the production/demo VehicleGateway until authentication
 * and read-only vehicle status have been verified on a real Citroen e-C3.
 *
 * No credentials, tokens or mobile-app secrets are stored in source control.
 */
data class StellantisDiagnosticState(
    val authentication: Check = Check.PENDING,
    val vehicleDiscovery: Check = Check.PENDING,
    val vehicleStatus: Check = Check.PENDING,
    val batteryPercent: Int? = null,
    val rangeKm: Int? = null,
    val charging: Boolean? = null,
    val message: String = "Experimental connector not authenticated"
) {
    enum class Check { PENDING, OK, ERROR }
}

interface StellantisCommunityDiagnostic {
    /**
     * Read-only diagnostic. It must never issue vehicle commands.
     */
    suspend fun readStatus(): StellantisDiagnosticState
}

class SafeStellantisCommunityDiagnostic : StellantisCommunityDiagnostic {
    override suspend fun readStatus(): StellantisDiagnosticState =
        StellantisDiagnosticState(
            message = "OAuth not configured. Demo app remains untouched."
        )
}
