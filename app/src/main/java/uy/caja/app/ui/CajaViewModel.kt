package uy.caja.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import uy.caja.app.data.*
import java.time.LocalDate

data class JornadaUiState(
    val fecha: LocalDate,
    val jornada: Jornada? = null,
    val habitaciones: List<Habitacion> = emptyList(),
    val ventas: List<VentaDb> = emptyList(),
    val cierre: Cierre? = null,
    val cargando: Boolean = true
)

class CajaViewModel(private val dao: CajaDao) : ViewModel() {
    private val fechaSeleccionada = MutableStateFlow(LocalDate.now())
    val fecha: StateFlow<LocalDate> = fechaSeleccionada.asStateFlow()
    val articulos: StateFlow<List<Articulo>> = dao.articulos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val jornada: StateFlow<JornadaUiState> = fechaSeleccionada
        .flatMapLatest { fecha ->
            flow {
                emit(JornadaUiState(fecha = fecha, cargando = true))
                dao.crearJornada(Jornada(fecha = fecha.toString()))
                val jornada = dao.jornadaFecha(fecha.toString())
                if (jornada == null) {
                    emit(JornadaUiState(fecha = fecha, cargando = false))
                } else {
                    emitAll(
                        combine(
                            dao.habitaciones(jornada.id),
                            dao.ventas(jornada.id),
                            dao.cierre(jornada.id)
                        ) { habitaciones, ventas, cierre ->
                            JornadaUiState(fecha, jornada, habitaciones, ventas, cierre, cargando = false)
                        }
                    )
                }
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            JornadaUiState(LocalDate.now())
        )

    fun seleccionarFecha(fecha: LocalDate) { fechaSeleccionada.value = fecha }

    fun guardarHabitacion(id: Long?, numero: String, monto: Long, pago: String) {
        val actual = jornada.value.jornada ?: return
        viewModelScope.launch {
            if (id == null) dao.insertarHabitacion(
                Habitacion(jornadaId = actual.id, numero = numero.trim(), persona = "", importe = monto, pago = pago)
            ) else jornada.value.habitaciones.firstOrNull { it.id == id }?.let { existente ->
                dao.actualizarHabitacion(existente.copy(numero = numero.trim(), importe = monto, pago = pago))
            }
        }
    }

    fun eliminarHabitacion(item: Habitacion) { viewModelScope.launch { dao.borrarHabitacion(item) } }

    fun guardarArticulo(id: Long?, nombre: String, precio: Long) {
        viewModelScope.launch {
            if (id == null) dao.insertarArticulo(Articulo(nombre = nombre.trim(), precio = precio))
            else articulos.value.firstOrNull { it.id == id }?.let { dao.actualizarArticulo(it.copy(nombre = nombre.trim(), precio = precio)) }
        }
    }

    fun eliminarArticulo(id: Long) { viewModelScope.launch { dao.desactivarArticulo(id) } }

    fun registrarVenta(articulo: Articulo, cantidad: Int, pago: String) {
        val actual = jornada.value.jornada ?: return
        viewModelScope.launch {
            dao.insertarVenta(
                VentaDb(
                    jornadaId = actual.id,
                    articuloId = articulo.id,
                    articuloNombre = articulo.nombre,
                    cantidad = cantidad,
                    precioAplicado = articulo.precio,
                    pago = pago
                )
            )
        }
    }

    fun eliminarVenta(item: VentaDb) { viewModelScope.launch { dao.borrarVenta(item) } }

    fun guardarCierre(efectivo: Long?, tarjeta: Long?) {
        val actual = jornada.value.jornada ?: return
        viewModelScope.launch { dao.guardarCierre(Cierre(actual.id, efectivo, tarjeta)) }
    }
}
