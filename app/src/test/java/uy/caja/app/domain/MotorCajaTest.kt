package uy.caja.app.domain
import org.junit.Assert.*
import org.junit.Test
class MotorCajaTest {
 @Test fun conciliaSeparadoYSinClasificar(){val r=MotorCaja.calcular(listOf(Linea(1000,Pago.EFECTIVO),Linea(500,Pago.SIN_CLASIFICAR)),listOf(Venta(2,200,Pago.TARJETA)),900,500);assertEquals(1000,r.efectivo);assertEquals(400,r.tarjeta);assertEquals(500,r.pendiente);assertEquals(100L,r.diferenciaEfectivo);assertEquals(-100L,r.diferenciaTarjeta);assertEquals(1900L,r.totalEsperado);assertEquals(1400L,r.totalInformado);assertEquals(500L,r.diferenciaTotal)}
 @Test fun cierreIncompletoNoEsCero(){val r=MotorCaja.calcular(emptyList(),emptyList(),null,0);assertNull(r.diferenciaEfectivo);assertEquals(0L,r.diferenciaTarjeta);assertNull(r.totalInformado);assertNull(r.diferenciaTotal)}
 @Test(expected=ArithmeticException::class) fun detectsOverflow(){MotorCaja.calcular(emptyList(),listOf(Venta(Int.MAX_VALUE,Long.MAX_VALUE,Pago.EFECTIVO)),null,null)}
}

