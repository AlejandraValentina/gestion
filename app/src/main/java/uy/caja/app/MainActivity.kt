package uy.caja.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bed
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import uy.caja.app.data.*
import uy.caja.app.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

class CajaVm(private val dao:CajaDao):ViewModel(){
 val jornadas=dao.jornadas().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val articulos=dao.articulos().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
 val fecha=MutableStateFlow(LocalDate.now().toString()); val jornada=MutableStateFlow<Jornada?>(null)
 val habitaciones=MutableStateFlow<List<Habitacion>>(emptyList()); val ventas=MutableStateFlow<List<VentaDb>>(emptyList()); val cierre=MutableStateFlow<Cierre?>(null)
 init {
  viewModelScope.launch {
   fecha.collectLatest { selectedDate ->
    // La jornada anterior deja de emitir antes de observar la nueva.
    jornada.value = null
    habitaciones.value = emptyList()
    ventas.value = emptyList()
    cierre.value = null
    dao.crearJornada(Jornada(fecha = selectedDate))
    val selected = dao.jornadaFecha(selectedDate)
    jornada.value = selected
    if (selected != null) coroutineScope {
     launch { dao.habitaciones(selected.id).collect { habitaciones.value = it } }
     launch { dao.ventas(selected.id).collect { ventas.value = it } }
     launch { dao.cierre(selected.id).collect { cierre.value = it } }
    }
   }
  }
 }
 fun fecha(f:String){ if(runCatching{LocalDate.parse(f)}.isSuccess) fecha.value=f }
 fun guardarHabitacion(id:Long?,num:String,persona:String,monto:String,pago:String){val j=jornada.value?:return; val n=monto.toLongOrNull()?.takeIf{it>=0}?:return; viewModelScope.launch{if(id==null)dao.insertarHabitacion(Habitacion(jornadaId=j.id,numero=num,persona=persona,importe=n,pago=pago)) else {val old=habitaciones.value.firstOrNull{it.id==id}?:return@launch;dao.actualizarHabitacion(old.copy(numero=num,persona=persona,importe=n,pago=pago))}}}
 fun borrar(h:Habitacion)=viewModelScope.launch{dao.borrarHabitacion(h)}
 fun guardarArticulo(nombre:String,precio:String,id:Long?=null){val n=precio.toLongOrNull()?.takeIf{it>=0}?:return;viewModelScope.launch{if(id==null)dao.insertarArticulo(Articulo(nombre=nombre,precio=n)) else dao.actualizarArticulo(articulos.value.first{it.id==id}.copy(nombre=nombre,precio=n))}}
 fun eliminarArticulo(id:Long)=viewModelScope.launch{dao.desactivarArticulo(id)}
 fun vender(a:Articulo,cantidad:String,pago:String){val j=jornada.value?:return; val n=cantidad.toIntOrNull()?.takeIf{it>0}?:return;viewModelScope.launch{dao.insertarVenta(VentaDb(jornadaId=j.id,articuloId=a.id,articuloNombre=a.nombre,cantidad=n,precioAplicado=a.precio,pago=pago))}}
 fun borrarVenta(v:VentaDb)=viewModelScope.launch{dao.borrarVenta(v)}
 fun guardarCierre(e:String,t:String){val j=jornada.value?:return;val ev=e.toLongOrNull()?.takeIf{it>=0};val ta=t.toLongOrNull()?.takeIf{it>=0};viewModelScope.launch{dao.guardarCierre(Cierre(j.id,ev,ta))}}
}
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState); val dao=CajaDb.get(this).dao();setContent { val vm: CajaVm=viewModel(factory=object:ViewModelProvider.Factory{override fun <T:ViewModel> create(c:Class<T>):T= CajaVm(dao) as T}); MaterialTheme(colorScheme=lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF006B5D),secondary=androidx.compose.ui.graphics.Color(0xFF52645F),background=androidx.compose.ui.graphics.Color(0xFFF4FBF8))){CajaApp(vm)} }}}

@Composable
fun CajaApp(vm: CajaVm) {
    val fecha by vm.fecha.collectAsState()
    val hs by vm.habitaciones.collectAsState()
    val vs by vm.ventas.collectAsState()
    val arts by vm.articulos.collectAsState()
    val c by vm.cierre.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf("") }
    var editH by remember { mutableStateOf<Habitacion?>(null) }
    var editA by remember { mutableStateOf<Articulo?>(null) }

    val secciones = listOf(
        Triple("Resumen", Icons.Outlined.Home, 0),
        Triple("Habitaciones", Icons.Outlined.Bed, 1),
        Triple("Artículos", Icons.Outlined.ShoppingCart, 2),
        Triple("Cierre", Icons.Outlined.ReceiptLong, 3)
    )

    Scaffold(
        topBar = { TopAppBarSimple() },
        bottomBar = {
            NavigationBar {
                secciones.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            SelectorJornada(fecha) { vm.fecha(it) }
            Spacer(Modifier.height(16.dp))
            when (tab) {
                0 -> ResumenDiario(hs, vs, c) { tab = it }
                1 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Ingresos de habitaciones",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Button(onClick = { editH = null; dialog = "habitacion" }) { Text("Agregar") }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (hs.isEmpty()) {
                        Text("Todavía no hay habitaciones registradas en esta jornada.")
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(hs, key = { it.id }) { h ->
                            Card(
                                onClick = { editH = h; dialog = "habitacion" },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Row(Modifier.padding(14.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Habitación ${h.numero.ifBlank { "—" }} · ${h.persona.ifBlank { "Sin nombre" }}",
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text("${money(h.importe)} · ${h.pago}")
                                    }
                                    TextButton(onClick = { vm.borrar(h) }) { Text("Borrar") }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Catálogo y ventas",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Button(onClick = { editA = null; dialog = "articulo" }) { Text("Nuevo") }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Spacer(Modifier.height(10.dp))
                            Text("PRODUCTOS", style = MaterialTheme.typography.labelLarge)
                        }
                        items(arts, key = { "art-${it.id}" }) { a ->
                            var qty by remember(a.id) { mutableStateOf("1") }
                            var pay by remember(a.id) { mutableStateOf("EFECTIVO") }
                            var menu by remember(a.id) { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { editA = a; dialog = "articulo" },
                                    modifier = Modifier.weight(1f)
                                ) { Text("${a.nombre} · ${money(a.precio)}") }
                                OutlinedTextField(
                                    qty, { qty = it },
                                    modifier = Modifier.width(72.dp),
                                    label = { Text("Cant.") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                                Button(onClick = { vm.vender(a, qty, pay) }) { Text("Vender") }
                            }
                            TextButton(onClick = { menu = true }) { Text("Pago: $pay · Cambiar") }
                            DropdownMenu(menu, { menu = false }) {
                                listOf("EFECTIVO", "TARJETA", "SIN_CLASIFICAR").forEach {
                                    DropdownMenuItem(text = { Text(it) }, onClick = { pay = it; menu = false })
                                }
                            }
                        }
                        item {
                            Spacer(Modifier.height(12.dp))
                            Text("VENTAS DEL DÍA", style = MaterialTheme.typography.labelLarge)
                        }
                        items(vs, key = { "venta-${it.id}" }) { v ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    "${v.cantidad} × ${v.articuloNombre} · ${money(v.cantidad.toLong() * v.precioAplicado)} (${v.pago})",
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { vm.borrarVenta(v) }) { Text("Eliminar") }
                            }
                        }
                    }
                }
                3 -> {
                    Text("Cierre de caja", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Los valores informados se comparan con los ingresos, no se suman nuevamente.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    var ev by remember(fecha, c?.jornadaId, c?.efectivo, c?.tarjeta) { mutableStateOf(c?.efectivo?.toString().orEmpty()) }
                    var ta by remember(fecha, c?.jornadaId, c?.efectivo, c?.tarjeta) { mutableStateOf(c?.tarjeta?.toString().orEmpty()) }
                    OutlinedTextField(
                        ev, { ev = it }, label = { Text("Efectivo informado ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        ta, { ta = it }, label = { Text("Tarjeta informada ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.guardarCierre(ev, ta) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Guardar cierre")
                    }
                }
            }
        }
    }

    if (dialog == "habitacion") {
        HabitacionDialog(editH, { dialog = "" }) { numero, persona, monto, medio ->
            vm.guardarHabitacion(editH?.id, numero, persona, monto, medio)
            dialog = ""
        }
    }
    if (dialog == "articulo") {
        ArticuloDialog(editA, { dialog = "" }) { nombre, precio ->
            vm.guardarArticulo(nombre, precio, editA?.id)
            dialog = ""
        }
    }
}

/** Calendario en lugar de un campo ISO imposible de editar parcialmente. */
@Composable
fun SelectorJornada(fecha: String, cambiar: (String) -> Unit) {
    val context = LocalContext.current
    val date = LocalDate.parse(fecha)
    val formato = remember { DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", Locale("es", "UY")) }
    OutlinedCard(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day -> cambiar(LocalDate.of(year, month + 1, day).toString()) },
                date.year,
                date.monthValue - 1,
                date.dayOfMonth
            ).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.ReceiptLong, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text("Jornada", style = MaterialTheme.typography.labelMedium)
                Text(date.format(formato).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            }
            Text("Cambiar", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable fun TopAppBarSimple(){Surface(color=MaterialTheme.colorScheme.primary,modifier=Modifier.windowInsetsPadding(WindowInsets.statusBars)){Text("CAJA DIARIA",Modifier.fillMaxWidth().padding(16.dp),color=androidx.compose.ui.graphics.Color.White,fontWeight=FontWeight.Bold)}}
@Composable fun SummaryCard(label:String,value:Long?){Card(Modifier.fillMaxWidth().padding(vertical=3.dp)){Row(Modifier.padding(horizontal=14.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,Modifier.weight(1f));Text(value?.let{money(it)}?:"Sin informar",fontWeight=FontWeight.Bold)}}}
fun money(n:Long)="${String.format("%,d",n).replace(',', '.') } $"
fun pago(s:String)=runCatching{Pago.valueOf(s)}.getOrDefault(Pago.SIN_CLASIFICAR)
@Composable fun HabitacionDialog(h:Habitacion?,dismiss:()->Unit,save:(String,String,String,String)->Unit){var n by remember{mutableStateOf(h?.numero.orEmpty())};var p by remember{mutableStateOf(h?.persona.orEmpty())};var m by remember{mutableStateOf(h?.importe?.toString().orEmpty())};var pay by remember{mutableStateOf(h?.pago?:"SIN_CLASIFICAR")};AlertDialog(onDismissRequest=dismiss,title={Text(if(h==null)"Nueva habitación" else "Editar habitación")},text={Column{OutlinedTextField(n,{n=it},label={Text("Número")});OutlinedTextField(p,{p=it},label={Text("Persona (opcional)")});OutlinedTextField(m,{m=it},label={Text("Importe entero ($)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));var open by remember{mutableStateOf(false)};TextButton(onClick={open=true}){Text("Medio de pago: $pay")};DropdownMenu(open,{open=false}){listOf("EFECTIVO","TARJETA","SIN_CLASIFICAR").forEach{DropdownMenuItem(text={Text(it)},onClick={pay=it;open=false})}}}},confirmButton={TextButton(onClick={save(n,p,m,pay)}){Text("Guardar")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})}
@Composable fun ArticuloDialog(a:Articulo?,dismiss:()->Unit,save:(String,String)->Unit){var n by remember{mutableStateOf(a?.nombre.orEmpty())};var p by remember{mutableStateOf(a?.precio?.toString().orEmpty())};AlertDialog(onDismissRequest=dismiss,title={Text(if(a==null)"Nuevo artículo" else "Editar artículo")},text={Column{OutlinedTextField(n,{n=it},label={Text("Producto")});OutlinedTextField(p,{p=it},label={Text("Precio entero ($)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))}},confirmButton={TextButton(onClick={save(n,p)}){Text("Guardar")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})}






