package com.ec3control.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ec3control.core.model.VehicleSnapshot
import com.ec3control.data.demo.DemoVehicleGateway
import com.ec3control.core.vehicle.VehicleGateway
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class Tab(val label:String){HOME("Inicio"),BATTERY("Batería"),CHARGE("Carga"),CLIMATE("Clima"),VEHICLE("Vehículo")}

@Composable fun Ec3App(gateway: VehicleGateway = remember { DemoVehicleGateway() }){
 var tab by remember{mutableStateOf(Tab.HOME)}
 var snapshot by remember{mutableStateOf<VehicleSnapshot?>(null)}
 LaunchedEffect(Unit){snapshot=gateway.getVehicle()}
 Scaffold(bottomBar={NavigationBar{Tab.entries.forEach{item->NavigationBarItem(selected=tab==item,onClick={tab=item},icon={Text(when(item){Tab.HOME->"⌂";Tab.BATTERY->"▣";Tab.CHARGE->"⚡";Tab.CLIMATE->"❄";Tab.VEHICLE->"●"})},label={Text(item.label)})}}}){padding->
  val mod=Modifier.padding(padding)
  when(tab){
   Tab.HOME->HomeScreen(gateway,snapshot,{snapshot=it},mod)
   Tab.BATTERY->BatteryScreen(snapshot,mod)
   Tab.CHARGE->ChargeScreen(gateway,snapshot,{snapshot=it},mod)
   Tab.CLIMATE->ClimateScreen(gateway,snapshot,{snapshot=it},mod)
   Tab.VEHICLE->VehicleScreen(snapshot,mod)
  }
 }
}

@Composable private fun Screen(title:String,snapshot:VehicleSnapshot?,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
 Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(title,style=MaterialTheme.typography.headlineMedium);AssistChip(onClick={},label={Text(if(snapshot?.origin?.name=="DEMO")"DEMO" else "REAL")})}
  content()
  if(snapshot?.origin?.name=="DEMO") Text("Datos de demostración · no proceden del vehículo",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
 }
}

@Composable private fun HomeScreen(gateway:VehicleGateway,snapshot:VehicleSnapshot?,setSnapshot:(VehicleSnapshot)->Unit,modifier:Modifier=Modifier){
 val scope=rememberCoroutineScope()
 Screen("Citroën ë-C3",snapshot,modifier){
  Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Batería",color=MaterialTheme.colorScheme.onSurfaceVariant);Text((snapshot?.batteryPercent?.toString()?:"--")+"%",style=MaterialTheme.typography.displayMedium);LinearProgressIndicator(progress={((snapshot?.batteryPercent?:0)/100f).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Autonomía",snapshot?.rangeKm?.let{it.toString()+" km"}?:"--");Metric("Kilómetros",snapshot?.odometerKm?.let{"%.0f km".format(it)}?:"--");Metric("SOH",snapshot?.batteryHealthPercent?.let{"%.0f%%".format(it)}?:"--")}}}
  Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp)){Text("Carga",style=MaterialTheme.typography.titleLarge);Spacer(Modifier.height(8.dp));Text(if(snapshot?.plugged==true) if(snapshot.charging==true)"Cargando" else "Cable conectado" else "Cable desconectado");Text(snapshot?.chargingPowerKw?.let{"%.1f kW".format(it)}?:"--",style=MaterialTheme.typography.headlineSmall)}}
  OutlinedButton(onClick={scope.launch{setSnapshot(gateway.refresh())}},modifier=Modifier.fillMaxWidth()){Text("Actualizar vehículo")}
 }
}

@Composable private fun BatteryScreen(s:VehicleSnapshot?,modifier:Modifier=Modifier)=Screen("Batería",s,modifier){
 Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Estado actual",style=MaterialTheme.typography.titleLarge);Text((s?.batteryPercent?.toString()?:"--")+"%",style=MaterialTheme.typography.displayMedium);LinearProgressIndicator(progress={((s?.batteryPercent?:0)/100f).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Metric("Autonomía",s?.rangeKm?.let{"$it km"}?:"--");Metric("Salud · SOH",s?.batteryHealthPercent?.let{"%.1f %%".format(it)}?:"No disponible")}}
 Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Histórico y ciclos",style=MaterialTheme.typography.titleLarge);Text("Los ciclos equivalentes se calcularán con la energía acumulada cuando exista histórico real.");Metric("Ciclos equivalentes","Pendiente de datos");Metric("Capacidad estimada","Pendiente de datos")}}
}

@Composable private fun ChargeScreen(gateway:VehicleGateway,s:VehicleSnapshot?,setSnapshot:(VehicleSnapshot)->Unit,modifier:Modifier=Modifier){
 val scope=rememberCoroutineScope()
 Screen("Carga",s,modifier){
  Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(if(s?.plugged==true)"Cable conectado" else "Cable desconectado",style=MaterialTheme.typography.titleLarge);Metric("Estado",if(s?.charging==true)"Cargando" else "En espera");Metric("Potencia",s?.chargingPowerKw?.let{"%.1f kW".format(it)}?:"--");Metric("Objetivo",s?.chargingTargetPercent?.let{"$it %"}?:"--");Button(enabled=s?.plugged==true,onClick={scope.launch{if(s?.charging==true)gateway.stopCharging() else gateway.startCharging();setSnapshot(gateway.getVehicle())}},modifier=Modifier.fillMaxWidth()){Text(if(s?.charging==true)"Detener carga" else "Iniciar carga")}}}
 }
}

@Composable private fun ClimateScreen(gateway:VehicleGateway,s:VehicleSnapshot?,setSnapshot:(VehicleSnapshot)->Unit,modifier:Modifier=Modifier){
 val scope=rememberCoroutineScope();var busy by remember{mutableStateOf(false)}
 Screen("Climatización",s,modifier){
  Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(if(s?.climateRunning==true)"Preclimatización encendida" else "Preclimatización apagada",style=MaterialTheme.typography.titleLarge);Metric("Temperatura exterior",s?.outsideTemperatureC?.let{"%.1f °C".format(it)}?:"--");Button(enabled=s!=null&&!busy,onClick={scope.launch{busy=true;gateway.setClimate(s?.climateRunning!=true);setSnapshot(gateway.getVehicle());busy=false}},modifier=Modifier.fillMaxWidth()){Text(if(s?.climateRunning==true)"Apagar climatización" else "Preclimatizar")};Text("En modo real, el estado solo se mostrará como confirmado después de recibir respuesta del vehículo.",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
 }
}

@Composable private fun VehicleScreen(s:VehicleSnapshot?,modifier:Modifier=Modifier)=Screen("Vehículo",s,modifier){
 Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Citroën ë-C3",style=MaterialTheme.typography.titleLarge);Metric("Kilometraje",s?.odometerKm?.let{"%.0f km".format(it)}?:"--");Metric("Temperatura exterior",s?.outsideTemperatureC?.let{"%.1f °C".format(it)}?:"--");Metric("Origen de datos",s?.origin?.name?:"--");Metric("Última actualización",s?.updatedAtEpochMillis?.let{DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it))}?:"--")}}
}

@Composable private fun Metric(label:String,value:String){Column{Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.titleMedium)}}
