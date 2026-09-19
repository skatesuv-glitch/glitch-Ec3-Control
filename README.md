# EC3-Control

Aplicación Android independiente para Citroën ë-C3.

## Objetivo V1
- Conexión segura con la cuenta/servicios Stellantis.
- Detectar el vehículo asociado.
- Estado de batería (SOC) y autonomía.
- Kilometraje.
- Estado y control de carga cuando el servicio lo permita.
- Preclimatización remota.
- SOH cuando esté disponible.
- Históricos locales de cargas y batería.
- Cálculo de ciclos equivalentes a partir del histórico energético.

## Arquitectura
La interfaz no depende directamente de Stellantis. Toda comunicación pasa por VehicleGateway.

- DemoVehicleGateway: desarrollo y pruebas sin vehículo.
- StellantisVehicleGateway: conexión real.
- VehicleSnapshot: modelo normalizado que consume la UI.

Los datos demo nunca deben presentarse como datos reales.

## Seguridad
No se almacenan contraseñas, PIN, VIN ni tokens en el repositorio. Las credenciales/tokens reales deberán almacenarse usando mecanismos seguros de Android.

## Estado
V1 en desarrollo.
