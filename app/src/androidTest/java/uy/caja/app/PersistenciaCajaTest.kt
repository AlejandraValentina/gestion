package uy.caja.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uy.caja.app.data.*

@RunWith(AndroidJUnit4::class)
class PersistenciaCajaTest {
    private lateinit var db: CajaDb
    private lateinit var dao: CajaDao
    private lateinit var nombreDb: String

    @Before fun abrir() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        nombreDb = "caja-instrumentada-${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, CajaDb::class.java, nombreDb).build()
        dao = db.dao()
    }

    @After fun cerrar() {
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(nombreDb)
    }

    @Test fun jornadasYMovimientosSobrevivenAlCerrarLaBase() = runBlocking {
        dao.crearJornada(Jornada(fecha = "2026-11-20"))
        dao.crearJornada(Jornada(fecha = "2026-11-21"))
        dao.crearJornada(Jornada(fecha = "2026-11-20"))
        assertEquals(2, dao.jornadas().first().size)
        val una = dao.jornadaFecha("2026-11-20")!!
        val otra = dao.jornadaFecha("2026-11-21")!!
        dao.insertarHabitacion(Habitacion(jornadaId = una.id, numero = "101", persona = "Ana", importe = 1200, pago = "EFECTIVO"))
        dao.guardarCierre(Cierre(una.id, efectivo = 1200, tarjeta = null))
        assertEquals(1, dao.habitaciones(una.id).first().size)
        assertEquals(0, dao.habitaciones(otra.id).first().size)
        assertEquals(1200L, dao.cierre(una.id).first()?.efectivo)
        assertEquals(null, dao.cierre(una.id).first()?.tarjeta)
        val dbName = db.openHelper.databaseName!!
        db.close()
        db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), CajaDb::class.java, dbName).build()
        dao = db.dao()
        assertEquals("101", dao.habitaciones(una.id).first().single().numero)
        assertEquals(0, dao.habitaciones(otra.id).first().size)
        assertEquals(1200L, dao.cierre(una.id).first()?.efectivo)
    }

    @Test fun operacionesDeCatalogoVentaYEdicionMantienenPrecioHistorico() = runBlocking {
        dao.crearJornada(Jornada(fecha = "2026-11-22"))
        val jornada = dao.jornadaFecha("2026-11-22")!!
        dao.insertarArticulo(Articulo(nombre = "Agua", precio = 55))
        val producto = dao.articulos().first().single()
        dao.insertarVenta(VentaDb(jornadaId = jornada.id, articuloId = producto.id, articuloNombre = producto.nombre, cantidad = 2, precioAplicado = producto.precio, pago = "TARJETA"))
        dao.actualizarArticulo(producto.copy(precio = 70))
        assertEquals(70L, dao.articulos().first().single().precio)
        assertEquals(55L, dao.ventas(jornada.id).first().single().precioAplicado)
        dao.borrarVenta(dao.ventas(jornada.id).first().single())
        assertEquals(0, dao.ventas(jornada.id).first().size)
        val ingreso = Habitacion(jornadaId = jornada.id, numero = "5", persona = "", importe = 800, pago = "SIN_CLASIFICAR")
        dao.insertarHabitacion(ingreso)
        val saved = dao.habitaciones(jornada.id).first().single()
        dao.actualizarHabitacion(saved.copy(importe = 900))
        assertEquals(900L, dao.habitaciones(jornada.id).first().single().importe)
        dao.borrarHabitacion(dao.habitaciones(jornada.id).first().single())
        dao.desactivarArticulo(producto.id)
        assertEquals(0, dao.habitaciones(jornada.id).first().size)
        assertEquals(0, dao.articulos().first().size)

    }
}

