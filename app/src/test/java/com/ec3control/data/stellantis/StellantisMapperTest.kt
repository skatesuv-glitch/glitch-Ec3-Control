package com.ec3control.data.stellantis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StellantisMapperTest {
    @Test fun mapsElectricVehicleStatus() {
        val source = StellantisStatus(
            batteryLevelPercent = 65.0,
            electricRangeKm = 184,
            odometerKm = 40305.8,
            batteryHealthPercent = 92.0,
            batteryCapacityWh = 43800.0,
            batteryResidualWh = 28470.0,
            plugged = true,
            chargingStatus = "Finished",
            chargingRate = 0.0,
            chargingRemainingTime = "PT0S",
            preconditioningStatus = "Disabled",
            outsideTemperatureC = 21.0,
            updatedAtEpochMillis = 1L
        )
        val result = StellantisMapper.toSnapshot(source)
        assertEquals(65, result.batteryPercent)
        assertEquals(184, result.rangeKm)
        assertEquals(40305.8, result.odometerKm)
        assertEquals(92.0, result.batteryHealthPercent)
        assertTrue(result.plugged == true)
    }
}
