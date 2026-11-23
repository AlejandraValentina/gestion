package uy.caja.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
 init { viewModelScope.launch { fecha.collect { f-> dao.crearJornada(Jornada(fecha=f)); jornada.value=dao.jornadaFecha(f); jornada.value?.let { j-> launch { dao.habitaciones(j.id).collect{habitaciones.value=it} }; launch { dao.ventas(j.id).collect{ventas.value=it} }; launch { dao.cierre(j.id).collect{cierre.value=it} } } } } }
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

@Composable fun CajaApp(vm:CajaVm){val fecha by vm.fecha.collectAsState();val j by vm.jornada.collectAsState();val hs by vm.habitaciones.collectAsState();val vs by vm.ventas.collectAsState();val arts by vm.articulos.collectAsState();val c by vm.cierre.collectAsState();var tab by remember{mutableIntStateOf(0)};var dialog by remember{mutableStateOf("")};var editH by remember{mutableStateOf<Habitacion?>(null)};var editA by remember{mutableStateOf<Articulo?>(null)}
 Scaffold(topBar={TopAppBarSimple()},bottomBar={NavigationBar(modifier=Modifier.windowInsetsPadding(WindowInsets.navigationBars)){listOf("Resumen","Habitaciones","Artículos","Cierre").forEachIndexed{i,s->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={},label={Text(s)})}}}){pad->Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=18.dp)){Spacer(Modifier.height(12.dp));OutlinedTextField(fecha,{vm.fecha(it)},label={Text("Fecha (AAAA-MM-DD)")},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(10.dp));when(tab){0->{val result=MotorCaja.calcular(hs.map{Linea(it.importe,pago(it.pago))},vs.map{Venta(it.cantidad,it.precioAplicado,pago(it.pago))},c?.efectivo,c?.tarjeta);Text("Jornada ${fecha}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));SummaryCard("Habitaciones",hs.sumOf{it.importe});SummaryCard("Artículos",vs.sumOf{it.cantidad.toLong()*it.precioAplicado});SummaryCard("Esperado efectivo",result.efectivo);SummaryCard("Esperado tarjeta",result.tarjeta);SummaryCard("Pendiente de clasificar",result.pendiente);SummaryCard("Diferencia efectivo",result.diferenciaEfectivo);SummaryCard("Diferencia tarjeta",result.diferenciaTarjeta);Text(if(result.efectivoInformado==null||result.tarjetaInformado==null)"Cierre incompleto" else if(result.pendiente>0)"Conciliación provisional: hay movimientos sin clasificar" else "Cierre informado",color=MaterialTheme.colorScheme.primary)}
1->{Row {Text("Ingresos de habitaciones",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);Button(onClick={editH=null;dialog="habitacion"}){Text("Agregar")}};LazyColumn {items(hs){h->Card(onClick={editH=h;dialog="habitacion"},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)){Row(Modifier.padding(14.dp)){Column(Modifier.weight(1f)){Text("Habitación ${h.numero.ifBlank{"—"}} · ${h.persona.ifBlank{"Sin nombre"}}",fontWeight=FontWeight.Bold);Text("${money(h.importe)} · ${h.pago}")};TextButton(onClick={vm.borrar(h)}){Text("Borrar")}}}}}}
2->{Row{Text("Catálogo y ventas",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);Button(onClick={editA=null;dialog="articulo"}){Text("Nuevo")}};LazyColumn{item{Text("PRODUCTOS",style=MaterialTheme.typography.labelLarge)};items(arts){a->var qty by remember(a.id){mutableStateOf("1")};var pay by remember(a.id){mutableStateOf("EFECTIVO")};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={editA=a;dialog="articulo"}){Text("${a.nombre} · ${money(a.precio)}")};OutlinedTextField(qty,{qty=it},modifier=Modifier.width(64.dp),label={Text("Cant.")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));Button(onClick={vm.vender(a,qty,pay)}){Text("Vender")}};var choice by remember(a.id){mutableStateOf(false)};TextButton(onClick={choice=true}){Text("Pago: $pay · Cambiar")};DropdownMenu(choice,{choice=false}){listOf("EFECTIVO","TARJETA","SIN_CLASIFICAR").forEach{DropdownMenuItem(text={Text(it)},onClick={pay=it;choice=false})}}};item{Text("VENTAS DEL DÍA",style=MaterialTheme.typography.labelLarge)};items(vs){v->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${v.cantidad} × ${v.articuloNombre} · ${money(v.cantidad.toLong()*v.precioAplicado)} (${v.pago})");TextButton(onClick={vm.borrarVenta(v)}){Text("Eliminar")}}}}}
3->{Text("Cierre de caja",style=MaterialTheme.typography.headlineSmall);Text("Valores informados para comparar; no se suman a los ingresos.");var ev by remember(c?.jornadaId){mutableStateOf(c?.efectivo?.toString().orEmpty())};var ta by remember(c?.jornadaId){mutableStateOf(c?.tarjeta?.toString().orEmpty())};OutlinedTextField(ev,{ev=it},label={Text("Efectivo informado ($)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth());OutlinedTextField(ta,{ta=it},label={Text("Tarjeta informada ($)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth());Button(onClick={vm.guardarCierre(ev,ta)}){Text("Guardar cierre")}}
}}}
if(dialog=="habitacion")HabitacionDialog(editH,{dialog=""}){n,p,m,pa->vm.guardarHabitacion(editH?.id,n,p,m,pa);dialog=""}
if(dialog=="articulo")ArticuloDialog(editA,{dialog=""}){n,p->vm.guardarArticulo(n,p,editA?.id);dialog=""}
}
@Composable fun TopAppBarSimple(){Surface(color=MaterialTheme.colorScheme.primary,modifier=Modifier.windowInsetsPadding(WindowInsets.statusBars)){Text("CAJA DIARIA",Modifier.fillMaxWidth().padding(16.dp),color=androidx.compose.ui.graphics.Color.White,fontWeight=FontWeight.Bold)}}
@Composable fun SummaryCard(label:String,value:Long?){Card(Modifier.fillMaxWidth().padding(vertical=3.dp)){Row(Modifier.padding(horizontal=14.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(label,Modifier.weight(1f));Text(value?.let{money(it)}?:"Sin informar",fontWeight=FontWeight.Bold)}}}
fun money(n:Long)="${String.format("%,d",n).replace(',', '.') } $"
fun pago(s:String)=runCatching{Pago.valueOf(s)}.getOrDefault(Pago.SIN_CLASIFICAR)
@Composable fun HabitacionDialog(h:Habitacion?,dismiss:()->Unit,save:(String,String,String,String)->Unit){var n by remember{mutableStateOf(h?.numero.orEmpty())};var p by remember{mutableStateOf(h?.persona.orEmpty())};var m by remember{mutableStateOf(h?.importe?.toString().orEmpty())};var pay by remember{mutableStateOf(h?.pago?:"SIN_CLASIFICAR")};AlertDialog(onDismissRequest=dismiss,title={Text(if(h==null)"Nueva habitación" else "Editar habitación")},text={Column{OutlinedTextField(n,{n=it},label={Text("Número")});OutlinedTextField(p,{p=it},label={Text("Persona (opcional)")});OutlinedTextField(m,{m=it},label={Text("Importe entero ($)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));var open by remember{mutableStateOf(false)};TextButton(onClick={open=true}){Text("Medio de pago: $pay")};DropdownMenu(open,{open=false}){listOf("EFECTIVO","TARJETA","SIN_CLASIFICAR").forEach{DropdownMenuItem(text={Text(it)},onClick={pay=it;open=false})}}}},confirmButton={TextButton(onClick={save(n,p,m,pay)}){Text("Guardar")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})}
@Composable fun ArticuloDialog(a:Articulo?,dismiss:()->Unit,save:(String,String)->Unit){var n by remember{mutableStateOf(a?.nombre.orEmpty())};var p by remember{mutableStateOf(a?.precio?.toString().orEmpty())};AlertDialog(onDismissRequest=dismiss,title={Text(if(a==null)"Nuevo artículo" else "Editar artículo")},text={Column{OutlinedTextField(n,{n=it},label={Text("Producto")});OutlinedTextField(p,{p=it},label={Text("Precio entero ($)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))}},confirmButton={TextButton(onClick={save(n,p)}){Text("Guardar")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})}






