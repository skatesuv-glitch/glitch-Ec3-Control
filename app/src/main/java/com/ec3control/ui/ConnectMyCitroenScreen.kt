package com.ec3control.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConnectMyCitroenScreen(
    state: ConnectionState,
    onConnect: () -> Unit,
    onSelectVehicle: (VehicleChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Conectar con MyCitroën", style = MaterialTheme.typography.headlineMedium)
        Text("EC3-Control utilizará tu sesión autorizada para leer el estado del ë-C3. La contraseña y los tokens no se guardan en el código.")

        when (state) {
            ConnectionState.Disconnected -> Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Conectar con MyCitroën") }
            ConnectionState.Connecting -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Conectando…") }
            is ConnectionState.Connected -> {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) {
                    Text("Vehículo conectado", style = MaterialTheme.typography.titleLarge)
                    Text(state.vehicleName)
                }}
            }
            is ConnectionState.VehicleSelectionRequired -> {
                Text("Selecciona tu vehículo")
                state.vehicles.forEach { vehicle ->
                    OutlinedButton(onClick = { onSelectVehicle(vehicle) }, modifier = Modifier.fillMaxWidth()) { Text(vehicle.label) }
                }
            }
            is ConnectionState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Reintentar") }
            }
        }
    }
}
