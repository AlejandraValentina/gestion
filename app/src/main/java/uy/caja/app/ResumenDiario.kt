package uy.caja.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import uy.caja.app.data.Cierre
import uy.caja.app.data.Habitacion
import uy.caja.app.data.VentaDb
import uy.caja.app.domain.Linea
import uy.caja.app.domain.MotorCaja
import uy.caja.app.domain.Venta

/**
 * El resumen solo presenta datos calculados por el motor contable.
 * Los importes del cierre nunca se suman como ingresos.
 */
@Composable
fun ResumenDiario(
    habitaciones: List<Habitacion>,
    ventas: List<VentaDb>,
    cierre: Cierre?,
    irA: (Int) -> Unit
) {
    val resultado = MotorCaja.calcular(
        habitaciones.map { Linea(it.importe, pago(it.pago)) },
        ventas.map { Venta(it.cantidad, it.precioAplicado, pago(it.pago)) },
        cierre?.efectivo,
        cierre?.tarjeta
    )
    val totalRegistrado = Math.addExact(
        Math.addExact(resultado.efectivo, resultado.tarjeta),
        resultado.pendiente
    )
    val habitacionesTotal = habitaciones.fold(0L) { acc, h -> Math.addExact(acc, h.importe) }
    val articulosTotal = ventas.fold(0L) { acc, v ->
        Math.addExact(acc, Math.multiplyExact(v.cantidad.toLong(), v.precioAplicado))
    }
    val cierreIncompleto = resultado.efectivoInformado == null || resultado.tarjetaInformado == null

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Resumen de la jornada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TOTAL REGISTRADO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    money(totalRegistrado),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Habitaciones y venta de artículos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Text("Origen de ingresos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OrigenCard(
                "Habitaciones", money(habitacionesTotal),
                "${habitaciones.size} registros", Modifier.weight(1f)
            ) { irA(1) }
            OrigenCard(
                "Artículos", money(articulosTotal),
                "${ventas.size} ventas", Modifier.weight(1f)
            ) { irA(2) }
        }

        Text("Conciliación por medio de pago", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ComparacionCard("Efectivo", resultado.efectivo, resultado.efectivoInformado, resultado.diferenciaEfectivo)
        ComparacionCard("Tarjeta", resultado.tarjeta, resultado.tarjetaInformado, resultado.diferenciaTarjeta)

        if (resultado.pendiente > 0L) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Importes sin clasificar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(money(resultado.pendiente), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Estos movimientos aún no se asignaron a efectivo ni tarjeta.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        cierreIncompleto -> "Cierre incompleto"
                        resultado.pendiente > 0L -> "Conciliación provisional"
                        resultado.diferenciaEfectivo == 0L && resultado.diferenciaTarjeta == 0L -> "Importes coincidentes"
                        else -> "Existen diferencias"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "La diferencia se calcula como importe esperado menos importe informado.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { irA(3) }) { Text(if (cierreIncompleto) "Informar cierre" else "Ver cierre") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RowScope.OrigenCard(
    titulo: String,
    importe: String,
    detalle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(titulo, style = MaterialTheme.typography.labelLarge)
            Text(importe, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComparacionCard(
    nombre: String,
    esperado: Long,
    informado: Long?,
    diferencia: Long?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(nombre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            ResumenFila("Esperado", money(esperado))
            ResumenFila("Informado", informado?.let(::money) ?: "Sin informar")
            HorizontalDivider()
            ResumenFila(
                "Diferencia",
                diferencia?.let(::money) ?: "Pendiente",
                destacar = true,
                alerta = diferencia != null && diferencia != 0L
            )
        }
    }
}

@Composable
private fun ResumenFila(
    titulo: String,
    valor: String,
    destacar: Boolean = false,
    alerta: Boolean = false
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(titulo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            valor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (destacar) FontWeight.Bold else FontWeight.Medium,
            color = if (alerta) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
