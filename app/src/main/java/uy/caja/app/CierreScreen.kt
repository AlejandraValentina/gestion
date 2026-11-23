package uy.caja.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import uy.caja.app.data.Jornada
import uy.caja.app.domain.Linea
import uy.caja.app.domain.Venta
import uy.caja.app.ui.CajaViewModel
import uy.caja.app.ui.JornadaUiState

@Composable
fun CierreScreen(jornada: JornadaUiState, vm: CajaViewModel) {
    val cierre = jornada.cierre
    var efectivo by remember(jornada.fecha, cierre?.efectivo, cierre?.tarjeta) { mutableStateOf(cierre?.efectivo?.toString().orEmpty()) }
    var tarjeta by remember(jornada.fecha, cierre?.efectivo, cierre?.tarjeta) { mutableStateOf(cierre?.tarjeta?.toString().orEmpty()) }
    val efectivoValor = efectivo.takeIf { it.isNotBlank() }?.toLongOrNull()
    val tarjetaValor = tarjeta.takeIf { it.isNotBlank() }?.toLongOrNull()
    val error = when {
        efectivo.isNotBlank() && (efectivoValor == null || efectivoValor < 0) -> "Revisá el importe de efectivo."
        tarjeta.isNotBlank() && (tarjetaValor == null || tarjetaValor < 0) -> "Revisá el importe de tarjeta."
        else -> null
    }
    val resultado = remember(jornada.habitaciones, jornada.ventas, efectivoValor, tarjetaValor) {
        uy.caja.app.domain.MotorCaja.calcular(
            jornada.habitaciones.map { Linea(it.importe, pago(it.pago)) },
            jornada.ventas.map { venta -> Venta(venta.cantidad, venta.precioAplicado, pago(venta.pago)) },
            efectivoValor,
            tarjetaValor
        )
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cierre de caja", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Ingresá los totales que informa el cierre. Se comparan con los movimientos y no se suman como ingresos.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            efectivo, { efectivo = it.filter(Char::isDigit) }, label = { Text("Efectivo informado") },
            placeholder = { Text("Dejar vacío si aún no está informado") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            tarjeta, { tarjeta = it.filter(Char::isDigit) }, label = { Text("Tarjeta informada") },
            placeholder = { Text("Dejar vacío si aún no está informado") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
        Text("Diferencias actuales", style = MaterialTheme.typography.titleMedium)
        CierreResultado("Efectivo", resultado.efectivo, efectivoValor, resultado.diferenciaEfectivo)
        CierreResultado("Tarjeta", resultado.tarjeta, tarjetaValor, resultado.diferenciaTarjeta)
        if (resultado.pendiente > 0) {
            Text("${money(resultado.pendiente)} pendientes de clasificar", color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { vm.guardarCierre(efectivoValor, tarjetaValor); keyboard?.hide(); focus.clearFocus() },
            enabled = error == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Guardar cierre") }
    }
}

@Composable
private fun CierreResultado(titulo: String, esperado: Long, informado: Long?, diferencia: Long?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FilaCierre("Esperado", money(esperado))
            FilaCierre("Informado", informado?.let(::money) ?: "Sin informar")
            FilaCierre("Diferencia", diferencia?.let(::money) ?: "Pendiente", true)
        }
    }
}

@Composable
private fun FilaCierre(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
