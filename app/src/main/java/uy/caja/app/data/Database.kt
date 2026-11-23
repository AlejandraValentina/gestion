package uy.caja.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName="jornadas", indices=[Index(value=["fecha"], unique=true)]) data class Jornada(@PrimaryKey(autoGenerate=true) val id:Long=0, val fecha:String)
@Entity(tableName="habitaciones") data class Habitacion(@PrimaryKey(autoGenerate=true) val id:Long=0, val jornadaId:Long, val numero:String, val persona:String, val importe:Long, val pago:String, val scanBatchId:String?=null)
@Entity(tableName="articulos") data class Articulo(@PrimaryKey(autoGenerate=true) val id:Long=0, val nombre:String, val precio:Long, val activo:Boolean=true)
@Entity(tableName="ventas") data class VentaDb(@PrimaryKey(autoGenerate=true) val id:Long=0, val jornadaId:Long, val articuloId:Long, val articuloNombre:String, val cantidad:Int, val precioAplicado:Long, val pago:String)
@Entity(tableName="cierres") data class Cierre(@PrimaryKey val jornadaId:Long, val efectivo:Long?, val tarjeta:Long?)
@Dao interface CajaDao {
 @Query("SELECT * FROM jornadas ORDER BY fecha DESC") fun jornadas():Flow<List<Jornada>>
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun crearJornada(j:Jornada):Long
 @Query("SELECT * FROM jornadas WHERE fecha=:fecha LIMIT 1") suspend fun jornadaFecha(fecha:String):Jornada?
 @Query("SELECT * FROM habitaciones WHERE jornadaId=:id ORDER BY id DESC") fun habitaciones(id:Long):Flow<List<Habitacion>>
 @Insert suspend fun insertarHabitacion(h:Habitacion)
 @Insert suspend fun insertarHabitaciones(habitaciones:List<Habitacion>)
 @Query("SELECT EXISTS(SELECT 1 FROM habitaciones WHERE scanBatchId=:batchId LIMIT 1)") suspend fun loteEscaneoExiste(batchId:String):Boolean
 @Transaction suspend fun importarHabitaciones(batchId:String, habitaciones:List<Habitacion>):Boolean {
  if (habitaciones.isEmpty() || loteEscaneoExiste(batchId)) return false
  insertarHabitaciones(habitaciones.map { it.copy(scanBatchId=batchId) })
  return true
 }
 @Update suspend fun actualizarHabitacion(h:Habitacion)
 @Delete suspend fun borrarHabitacion(h:Habitacion)
 @Query("SELECT * FROM articulos WHERE activo=1 ORDER BY nombre") fun articulos():Flow<List<Articulo>>
 @Insert suspend fun insertarArticulo(a:Articulo)
 @Update suspend fun actualizarArticulo(a:Articulo)
 @Query("UPDATE articulos SET activo=0 WHERE id=:id") suspend fun desactivarArticulo(id:Long)
 @Query("SELECT * FROM ventas WHERE jornadaId=:id ORDER BY id DESC") fun ventas(id:Long):Flow<List<VentaDb>>
 @Insert suspend fun insertarVenta(v:VentaDb)
 @Delete suspend fun borrarVenta(v:VentaDb)
 @Query("SELECT * FROM cierres WHERE jornadaId=:id") fun cierre(id:Long):Flow<Cierre?>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun guardarCierre(c:Cierre)
}
@Database(entities=[Jornada::class,Habitacion::class,Articulo::class,VentaDb::class,Cierre::class],version=2,exportSchema=false) abstract class CajaDb:RoomDatabase(){abstract fun dao():CajaDao; companion object {
 @Volatile private var INSTANCE:CajaDb?=null
 val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE habitaciones ADD COLUMN scanBatchId TEXT")}}
 fun get(c:Context)=INSTANCE?: synchronized(this){INSTANCE?:Room.databaseBuilder(c.applicationContext,CajaDb::class.java,"caja.db").addMigrations(MIGRATION_1_2).build().also{INSTANCE=it}}
} }



