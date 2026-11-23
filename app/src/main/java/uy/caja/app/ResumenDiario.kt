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
import uy.caja.app.domain.Pago

@Composable
fun ResumenDiario(habitaciones: List<Habitacion>, ventas: List<VentaDb>, cierre: Cierre?, irA: (Int) -> Unit) {
    val resultado = MotorCaja.calcular(
        habitaciones.map { Linea(it.importe, pago(it.pago)) },
        ventas.map { Venta(it.cantidad, it.precioAplicado, pago(it.pago)) },
        cierre?.efectivo,
        cierre?.tarjeta
    )
    val total = resultado.totalEsperado
    val habitacionesTotal = habitaciones.fold(0L) { acc, h -> Math.addExact(acc, h.importe) }
    val articulosTotal = ventas.fold(0L) { acc, v -> Math.addExact(acc, Math.multiplyExact(v.cantidad.toLong(), v.precioAplicado)) }
    val cierreIncompleto = resultado.efectivoInformado == null || resultado.tarjetaInformado == null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Resumen de la jornada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TOTAL REGISTRADO", style = MaterialTheme.typography.labelMedium)
                Text(money(total), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Habitaciones y artículos", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Ingresos por origen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OrigenCard("Habitaciones", money(habitacionesTotal), "${habitaciones.size} registros", Modifier.weight(1f)) { irA(1) }
            OrigenCard("Artículos", money(articulosTotal), "${ventas.size} ventas", Modifier.weight(1f)) { irA(2) }
        }
        Text("Conciliación", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ComparacionCard("Efectivo", resultado.efectivo, resultado.efectivoInformado, resultado.diferenciaEfectivo)
        ComparacionCard("Tarjeta", resultado.tarjeta, resultado.tarjetaInformado, resultado.diferenciaTarjeta)
        if (resultado.pendiente > 0L) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Pendiente de clasificar", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(money(resultado.pendiente), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("No se asigna automáticamente a efectivo ni tarjeta.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        cierreIncompleto -> "Cierre incompleto"
                        resultado.pendiente > 0L -> "Conciliación provisional"
                        resultado.diferenciaEfectivo == 0L && resultado.diferenciaTarjeta == 0L -> "Importes coincidentes"
                        else -> "Existen diferencias"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                FilaResumen("Total de habitaciones + artículos", money(total))
                FilaResumen("Efectivo + tarjeta informado", resultado.totalInformado?.let(::money) ?: "Pendiente (cierre incompleto)")
                FilaResumen("Diferencia total", resultado.diferenciaTotal?.let(::money) ?: "Pendiente", destacar = true, alerta = resultado.diferenciaTotal != null && resultado.diferenciaTotal != 0L)
                Text("Diferencia = esperado − informado", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { irA(3) }) { Text(if (cierreIncompleto) "Completar cierre" else "Ver cierre") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RowScope.OrigenCard(titulo: String, importe: String, detalle: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(titulo, style = MaterialTheme.typography.labelLarge)
            Text(importe, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detalle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ComparacionCard(nombre: String, esperado: Long, informado: Long?, diferencia: Long?) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(nombre, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FilaResumen("Esperado", money(esperado))
            FilaResumen("Informado", informado?.let(::money) ?: "Sin informar")
            HorizontalDivider()
            FilaResumen("Diferencia", diferencia?.let(::money) ?: "Pendiente", destacar = true, alerta = diferencia != null && diferencia != 0L)
        }
    }
}

@Composable
private fun FilaResumen(titulo: String, valor: String, destacar: Boolean = false, alerta: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(titulo, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valor, fontWeight = if (destacar) FontWeight.Bold else FontWeight.Medium, color = if (alerta) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

fun pago(value: String): Pago = runCatching { Pago.valueOf(value) }.getOrDefault(Pago.SIN_CLASIFICAR)
