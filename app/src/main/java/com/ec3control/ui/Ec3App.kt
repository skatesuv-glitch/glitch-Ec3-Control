package com.ec3control.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ec3control.core.model.VehicleSnapshot
import com.ec3control.data.demo.DemoVehicleGateway
import kotlinx.coroutines.launch
private enum class Tab(val label:String){HOME("Inicio"),BATTERY("Batería"),CHARGE("Carga"),CLIMATE("Clima"),VEHICLE("Vehículo")}
@Composable fun Ec3App(){
 var tab by remember{mutableStateOf(Tab.HOME)}
 val gateway=remember{DemoVehicleGateway()}
 Scaffold(bottomBar={NavigationBar{Tab.entries.forEach{item->NavigationBarItem(selected=tab==item,onClick={tab=item},icon={Text(when(item){Tab.HOME->"⌂";Tab.BATTERY->"▣";Tab.CHARGE->"⚡";Tab.CLIMATE->"❄";Tab.VEHICLE->"●"})},label={Text(item.label)})}}}){padding->
  when(tab){Tab.HOME->HomeScreen(gateway,Modifier.padding(padding));else->PlaceholderScreen(tab.label,Modifier.padding(padding))}
 }
}
@Composable private fun HomeScreen(gateway:DemoVehicleGateway,modifier:Modifier=Modifier){
 val scope=rememberCoroutineScope();var snapshot by remember{mutableStateOf<VehicleSnapshot?>(null)};var busy by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){snapshot=gateway.getVehicle()}
 Column(modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text("Citroën ë-C3",style=MaterialTheme.typography.headlineMedium);Text("EC3-Control",color=MaterialTheme.colorScheme.onSurfaceVariant)};AssistChip(onClick={},label={Text(if(snapshot?.origin?.name=="DEMO")"DEMO" else "REAL")})}
  Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Batería",color=MaterialTheme.colorScheme.onSurfaceVariant);Text((snapshot?.batteryPercent?.toString()?:"--")+"%",style=MaterialTheme.typography.displayMedium);LinearProgressIndicator(progress={((snapshot?.batteryPercent?:0)/100f).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Metric("Autonomía",snapshot?.rangeKm?.let{it.toString()+" km"}?:"--");Metric("Kilómetros",snapshot?.odometerKm?.let{"%.0f km".format(it)}?:"--");Metric("SOH",snapshot?.batteryHealthPercent?.let{"%.0f%%".format(it)}?:"--")}}}
  Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Climatización",style=MaterialTheme.typography.titleLarge);Text(if(snapshot?.climateRunning==true)"Encendida" else "Apagada");Button(enabled=snapshot!=null&&!busy,onClick={scope.launch{busy=true;gateway.setClimate(snapshot?.climateRunning!=true);snapshot=gateway.getVehicle();busy=false}},modifier=Modifier.fillMaxWidth()){Text(if(snapshot?.climateRunning==true)"Apagar" else "Preclimatizar")}}}
  OutlinedButton(onClick={scope.launch{snapshot=gateway.refresh()}},modifier=Modifier.fillMaxWidth()){Text("Actualizar vehículo")}
  Text("Modo demostración. Ningún dato de esta pantalla procede todavía del vehículo.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
 }
}
@Composable private fun Metric(label:String,value:String){Column(horizontalAlignment=Alignment.Start){Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.titleMedium)}}
@Composable private fun PlaceholderScreen(name:String,modifier:Modifier=Modifier){Box(modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(name+" · V1",style=MaterialTheme.typography.headlineMedium)}}
