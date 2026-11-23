# Caja diaria (MVP 0)

Aplicación Android nativa en Kotlin, Jetpack Compose/Material 3 y Room. Los datos se guardan en SQLite local sin necesidad de conexión.

## Requisitos

- Android Studio actualizado y Android SDK API 35 o superior.
- JDK 17 (Gradle/AGP compatible).

## Abrir y ejecutar

Abra esta carpeta como proyecto Gradle en Android Studio, espere la sincronización, conecte un dispositivo/emulador (Android 6.0/API 23+) y ejecute la configuración `app`. Para compilar: `gradle assembleDebug`; pruebas de dominio: `gradle testDebugUnitTest`.

## Funcionalidades

- Jornadas creadas y consultadas por fecha, sin duplicar fechas.
- Altas, edición y eliminación de habitaciones; el importe exige pesos enteros no negativos y el pago se clasifica explícitamente.
- Catálogo editable con precio vigente y ventas con nombre/precio copiados al momento de vender.
- Cierre manual, separando valores no informados de cero.
- Conciliación determinista para efectivo/tarjeta, pendiente sin clasificar y diferencias esperado menos informado.

## Arquitectura

Interfaz Compose → ViewModel/StateFlow → DAO Room; `MotorCaja` y modelos puros en dominio, sin dependencias de UI ni de almacenamiento. Las fronteras para cámara, OCR y voz quedan para la iteración posterior: no hay permisos ni integraciones externas en esta versión.

## Limitaciones conocidas

- El total vendido se determina por el medio elegido en cada venta; no se infieren reglas sobre tickets parciales, retiros ni ajustes.
- El estado del cierre es visible por sus importes/diferencias, pero no existe una auditoría de cambios ni exportación/respaldo.
- La fecha se ingresa como AAAA-MM-DD.
- Se necesita validar la interfaz en un dispositivo real o emulador antes de distribuir.
