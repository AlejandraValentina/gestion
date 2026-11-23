package uy.caja.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uy.caja.app.data.*
import uy.caja.app.domain.*

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

    @Test fun importacionEscaneadaEsAtomicaIdempotenteYPermiteHabitacionRepetida() = runBlocking {
        dao.crearJornada(Jornada(fecha = "2026-11-23"))
        val jornada = dao.jornadaFecha("2026-11-23")!!
        val lote = "scan-test-${System.nanoTime()}"
        val filas = listOf(
            Habitacion(jornadaId = jornada.id, numero = "101", persona = "", importe = 500, pago = "SIN_CLASIFICAR"),
            Habitacion(jornadaId = jornada.id, numero = "101", persona = "", importe = 800, pago = "TARJETA")
        )

        assertTrue(dao.importarHabitaciones(lote, filas))
        assertFalse(dao.importarHabitaciones(lote, filas))

        val guardadas = dao.habitaciones(jornada.id).first()
        assertEquals(2, guardadas.size)
        assertEquals(setOf(500L, 800L), guardadas.map { it.importe }.toSet())
        assertTrue(guardadas.all { it.scanBatchId == lote })
        assertTrue(guardadas.all { it.persona.isEmpty() })
    }

    @Test fun importacionDeVentasEsAtomicaIdempotenteConservaPreciosYAlimentaConciliacion() = runBlocking {
        dao.crearJornada(Jornada(fecha = "2026-11-24"))
        val jornada = dao.jornadaFecha("2026-11-24")!!
        val productId = dao.insertarArticulo(Articulo(nombre = "Agua", precio = 80))
        val batch = "sales-scan-${System.nanoTime()}"
        val rows = listOf(
            VentaDb(jornadaId = jornada.id, articuloId = productId, articuloNombre = "Agua", cantidad = 3, precioAplicado = 80, pago = "EFECTIVO"),
            VentaDb(jornadaId = jornada.id, articuloId = productId, articuloNombre = "Agua", cantidad = 2, precioAplicado = 80, pago = "SIN_CLASIFICAR")
        )
        assertTrue(dao.importarVentasEscaneadas(batch, rows))
        assertFalse(dao.importarVentasEscaneadas(batch, rows))
        dao.actualizarArticulo(dao.articulos().first().single().copy(precio = 100))
        val saved = dao.ventas(jornada.id).first()
        assertEquals(2, saved.size)
        assertTrue(saved.all { it.scanBatchId == batch && it.precioAplicado == 80L })

        val reconciliation = MotorCaja.calcular(
            habitaciones = emptyList(),
            ventas = saved.map { Venta(it.cantidad, it.precioAplicado, Pago.valueOf(it.pago)) },
            efectivoCierre = null,
            tarjetaCierre = null
        )
        assertEquals(240L, reconciliation.efectivo)
        assertEquals(160L, reconciliation.pendiente)
        assertEquals(400L, reconciliation.efectivo + reconciliation.tarjeta + reconciliation.pendiente)
    }

    @Test fun migrationV1V2ConservaMovimientosYaGuardados() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacyName = "caja-v1-${System.nanoTime()}.db"
        nombreDb = legacyName
        val legacyFile = context.getDatabasePath(legacyName)
        legacyFile.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(legacyFile, null)
        legacy.execSQL("CREATE TABLE jornadas (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fecha TEXT NOT NULL)")
        legacy.execSQL("CREATE UNIQUE INDEX index_jornadas_fecha ON jornadas(fecha)")
        legacy.execSQL("CREATE TABLE habitaciones (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, jornadaId INTEGER NOT NULL, numero TEXT NOT NULL, persona TEXT NOT NULL, importe INTEGER NOT NULL, pago TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE articulos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, nombre TEXT NOT NULL, precio INTEGER NOT NULL, activo INTEGER NOT NULL)")
        legacy.execSQL("CREATE TABLE ventas (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, jornadaId INTEGER NOT NULL, articuloId INTEGER NOT NULL, articuloNombre TEXT NOT NULL, cantidad INTEGER NOT NULL, precioAplicado INTEGER NOT NULL, pago TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE cierres (jornadaId INTEGER NOT NULL PRIMARY KEY, efectivo INTEGER, tarjeta INTEGER)")
        legacy.execSQL("INSERT INTO jornadas (id, fecha) VALUES (1, '2026-11-23')")
        legacy.execSQL("INSERT INTO habitaciones (id, jornadaId, numero, persona, importe, pago) VALUES (1, 1, '101', 'Ana', 1200, 'SIN_CLASIFICAR')")
        legacy.execSQL("INSERT INTO articulos (id, nombre, precio, activo) VALUES (1, 'Agua', 55, 1)")
        legacy.execSQL("INSERT INTO ventas (id, jornadaId, articuloId, articuloNombre, cantidad, precioAplicado, pago) VALUES (1, 1, 1, 'Agua', 2, 55, 'TARJETA')")
        legacy.version = 1
        legacy.close()

        db = Room.databaseBuilder(context, CajaDb::class.java, legacyName)
            .addMigrations(CajaDb.MIGRATION_1_2, CajaDb.MIGRATION_2_3)
            .build()
        dao = db.dao()
        val migrated = dao.habitaciones(1).first().single()
        assertEquals("101", migrated.numero)
        assertEquals(1200L, migrated.importe)
        assertEquals(null, migrated.scanBatchId)
        val migratedSale = dao.ventas(1).first().single()
        assertEquals(55L, migratedSale.precioAplicado)
        assertEquals(null, migratedSale.scanBatchId)
    }
}

