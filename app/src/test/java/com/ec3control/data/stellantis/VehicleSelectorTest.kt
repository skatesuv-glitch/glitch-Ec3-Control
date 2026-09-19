package com.ec3control.data.stellantis

import kotlin.test.Test
import kotlin.test.assertIs

class VehicleSelectorTest {
    @Test fun selectsOnlyVehicleAutomatically() {
        val result = VehicleSelector.from(listOf(StellantisVehicle("1", null, "Citroën ë-C3")))
        assertIs<VehicleSelection.Selected>(result)
    }

    @Test fun requestsChoiceWhenSeveralVehiclesExist() {
        val result = VehicleSelector.from(listOf(
            StellantisVehicle("1", null, "ë-C3"),
            StellantisVehicle("2", null, "C4")
        ))
        assertIs<VehicleSelection.Choose>(result)
    }
}
