package uy.caja.app

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uy.caja.app.ui.CajaViewModel
import uy.caja.app.data.CajaDao
import uy.caja.app.ui.scan.HabitacionesScanFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Destino(val label: String, val icon: ImageVector, val index: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CajaApp(vm: CajaViewModel, dao: CajaDao, onExit: () -> Unit) {
    val jornada by vm.jornada.collectAsStateWithLifecycle()
    val articulos by vm.articulos.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmarSalida by rememberSaveable { mutableStateOf(false) }
    var escaneandoCuaderno by rememberSaveable { mutableStateOf(false) }
    val destinos = remember {
        listOf(
            Destino("Resumen", Icons.Outlined.Home, 0),
            Destino("Habitaciones", Icons.Outlined.Bed, 1),
            Destino("Artículos", Icons.Outlined.ShoppingCart, 2),
            Destino("Cierre", Icons.Outlined.ReceiptLong, 3)
        )
    }
    BackHandler(enabled = tab != 0 || escaneandoCuaderno) {
        if (escaneandoCuaderno) escaneandoCuaderno = false else tab = 0
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestión de Caja", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = { confirmarSalida = true }) {
                        Text("Salir", color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                destinos.forEach { destino ->
                    NavigationBarItem(
                        selected = tab == destino.index,
                        onClick = { tab = destino.index },
                        icon = { Icon(destino.icon, contentDescription = destino.label) },
                        label = { Text(destino.label) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)
        ) {
            SelectorJornada(jornada.fecha) { vm.seleccionarFecha(it) }
            Spacer(Modifier.height(12.dp))
            if (jornada.cargando) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else when (tab) {
                0 -> ResumenDiario(jornada.habitaciones, jornada.ventas, jornada.cierre) { tab = it }
                1 -> {
                    val jornadaActual = jornada.jornada
                    if (escaneandoCuaderno && jornadaActual != null) {
                        HabitacionesScanFlow(
                            jornadaId = jornadaActual.id,
                            fecha = jornada.fecha.toString(),
                            habitaciones = jornada.habitaciones,
                            dao = dao,
                            onClose = { escaneandoCuaderno = false }
                        )
                    } else {
                        HabitacionesScreen(jornada.habitaciones, vm, onScan = { escaneandoCuaderno = true })
                    }
                }
                2 -> ArticulosScreen(articulos, jornada.ventas, vm)
                3 -> CierreScreen(jornada, vm)
            }
        }
    }
    if (confirmarSalida) {
        AlertDialog(
            onDismissRequest = { confirmarSalida = false },
            title = { Text("Salir de la aplicación") },
            text = { Text("¿Querés cerrar Gestión de Caja?") },
            confirmButton = {
                TextButton(onClick = onExit) { Text("Salir") }
            },
            dismissButton = {
                TextButton(onClick = { confirmarSalida = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun SelectorJornada(fecha: LocalDate, onSelect: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val formatter = remember { DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", Locale("es", "UY")) }
    OutlinedCard(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day -> onSelect(LocalDate.of(year, month + 1, day)) },
                fecha.year,
                fecha.monthValue - 1,
                fecha.dayOfMonth
            ).show()
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Jornada", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fecha.format(formatter).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            }
            Text("Cambiar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

fun money(value: Long): String = "${String.format(Locale("es", "UY"), "%,d", value)} $"

@Composable
fun MensajeVacio(texto: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun pagoLabel(value: String): String = when (value) {
    "EFECTIVO" -> "Efectivo"
    "TARJETA" -> "Tarjeta"
    else -> "Sin clasificar"
}

