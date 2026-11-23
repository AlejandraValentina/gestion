# Gestión de Caja — Android

MVP nativo para registrar ingresos de habitaciones, ventas de artículos y cierres diarios, y conciliarlos por medio de pago. Los datos se guardan localmente en Room; la aplicación funciona sin conexión.

## Requisitos y ejecución

- Android Studio y Android SDK API 36 (la app sigue usando `targetSdk` 35).
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
- Escaneo de cuaderno: CameraX, selección y ajuste de una región, reconocimiento local y propuesta editable antes de guardar.
- Catálogo de productos separado de la lista de ventas. Cambiar un precio no altera los precios aplicados a ventas previas.
- Cierres manuales de efectivo y tarjeta. Campo vacío significa “sin informar”; cero es un valor informado.
- Resumen por origen, efectivo, tarjeta, pendientes, diferencias y estado de conciliación.
- El cierre se compara con los ingresos; no se suma como ingreso.

## Estructura

- `ui/CajaViewModel.kt`: selección de jornada y observación con `flatMapLatest`; al cambiar la fecha cancela la observación anterior y emite estado de carga antes de la nueva jornada.
- `CajaApp.kt`, `ResumenDiario.kt`, `HabitacionesScreen.kt`, `ArticulosScreen.kt`, `CierreScreen.kt`: navegación, componentes y pantallas Compose.
- `domain/MotorCaja.kt`: aritmética pura con `Long`, operaciones exactas y estados de pago explícitos.
- `ui/scan/HabitacionesScanScreen.kt`: permiso, preview CameraX, captura, recorte visual y revisión editable.
- `ui/scan/HabitacionesScanViewModel.kt`: ciclo del escaneo, procesamiento en segundo plano y confirmación del lote.
- `data/recognition`: contrato reemplazable y adaptador ML Kit Text Recognition v2 con modelo latino empaquetado.
- `domain/scan/RoomRecordParser.kt`: interpretación conservadora de líneas, importes enteros y medio de pago explícito.
- `data/Database.kt`: Room v2; migración 1→2 agrega la marca del lote y la importación transaccional impide confirmar dos veces el mismo escaneo.
- `src/test`: pruebas unitarias del motor contable y del parser.
- `src/androidTest`: pruebas Room de persistencia, migración, atomicidad e idempotencia.

## Estado de validación de esta revisión

- `testDebugUnitTest`: 6 pruebas correctas.
- `assembleDebug`: compilación correcta; produce el APK indicado arriba.
- `assembleDebugAndroidTest`: compilación correcta de las pruebas instrumentadas.
- `connectedDebugAndroidTest`: 4 pruebas correctas en Pixel 8/API 35, incluida la migración de datos existentes.
- El emulador abrió CameraX, capturó una imagen, permitió mover el recorte y procesó la región. La escena virtual del emulador no es una muestra de cuaderno manuscrito y no produjo registros; se canceló sin guardar datos.
- ML Kit trabaja localmente y sin descarga durante la solicitud. El modelo latino es OCR general, no un reconocedor especializado de manuscrito; la precisión real queda pendiente de medir con páginas representativas.
- Las fotos se conservan en el almacenamiento privado solo durante la revisión y se eliminan al cancelar o terminar el flujo. No se almacenan en Room.
- La fecha elegida queda asociada al escaneo; si se cambia durante la revisión, no se permite confirmar en otra jornada.
- El medio de pago ausente queda `SIN_CLASIFICAR`; nunca se asigna efectivo automáticamente.
