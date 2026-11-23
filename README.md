# Gestión de Caja — Android

MVP nativo para registrar ingresos de habitaciones, ventas de artículos y cierres diarios, y conciliarlos por medio de pago. Los datos se guardan localmente en Room; la aplicación funciona sin conexión.

## Requisitos y ejecución

- Android Studio y Android SDK API 35.
- JDK 17 o superior (el Gradle Wrapper fija Gradle 8.13).
- Dispositivo o emulador Android 6.0/API 23 o superior.

Abrí este directorio en Android Studio o ejecutá en Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/app-debug.apk`. Para ejecutar las pruebas instrumentadas de Room con un emulador conectado:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## Flujos

- Seleccionar una fecha con calendario; cada fecha identifica una jornada única.
- Alta, edición y eliminación de habitaciones; medio de pago explícito.
- Catálogo de productos separado de la lista de ventas. Cambiar un precio no altera los precios aplicados a ventas previas.
- Cierres manuales de efectivo y tarjeta. Campo vacío significa “sin informar”; cero es un valor informado.
- Resumen por origen, efectivo, tarjeta, pendientes, diferencias y estado de conciliación.
- El cierre se compara con los ingresos; no se suma como ingreso.

## Estructura

- `ui/CajaViewModel.kt`: selección de jornada y observación con `flatMapLatest`; al cambiar la fecha cancela la observación anterior y emite estado de carga antes de la nueva jornada.
- `CajaApp.kt`, `ResumenDiario.kt`, `HabitacionesScreen.kt`, `ArticulosScreen.kt`, `CierreScreen.kt`: navegación, componentes y pantallas Compose.
- `domain/MotorCaja.kt`: aritmética pura con `Long`, operaciones exactas y estados de pago explícitos.
- `data/Database.kt`: Room, DAO y repositorios locales. No se cambió el esquema ni se requiere migración.
- `src/test`: pruebas unitarias del motor contable.
- `src/androidTest`: pruebas Room para jornadas distintas, edición/eliminación y persistencia tras cerrar/reabrir la base.

## Estado de validación de esta revisión

- `testDebugUnitTest`: ejecutado correctamente.
- `assembleDebug`: compilación correcta; produce el APK indicado arriba.
- `assembleDebugAndroidTest`: compilación correcta de las pruebas instrumentadas.
- `connectedDebugAndroidTest`: no se pudo ejecutar. El AVD Pixel 8 disponible no arranca porque el volumen del sistema `C:` no tiene espacio suficiente. No se borraron datos para liberar espacio.
- No se implementan cámara, OCR, reconocimiento de voz ni servicios externos. Los cálculos permanecen independientes de futuras propuestas de lectura.
