package uy.caja.app.domain

enum class Pago { EFECTIVO, TARJETA, SIN_CLASIFICAR }
data class Linea(val importe: Long, val pago: Pago)
data class Venta(val cantidad: Int, val precio: Long, val pago: Pago)
data class Conciliacion(val efectivo: Long, val tarjeta: Long, val pendiente: Long, val efectivoInformado: Long?, val tarjetaInformado: Long?) {
    val diferenciaEfectivo get() = efectivoInformado?.let { efectivo - it }
    val diferenciaTarjeta get() = tarjetaInformado?.let { tarjeta - it }
    val totalEsperado get() = Math.addExact(Math.addExact(efectivo, tarjeta), pendiente)
    val totalInformado get() = if (efectivoInformado != null && tarjetaInformado != null) Math.addExact(efectivoInformado, tarjetaInformado) else null
    val diferenciaTotal get() = totalInformado?.let { totalEsperado - it }
}
object MotorCaja {
    fun calcular(habitaciones: List<Linea>, ventas: List<Venta>, efectivoCierre: Long?, tarjetaCierre: Long?): Conciliacion {
        var efectivo = 0L; var tarjeta = 0L; var pendiente = 0L
        habitaciones.forEach { require(it.importe >= 0); when(it.pago) { Pago.EFECTIVO -> efectivo = Math.addExact(efectivo,it.importe); Pago.TARJETA -> tarjeta = Math.addExact(tarjeta,it.importe); Pago.SIN_CLASIFICAR -> pendiente = Math.addExact(pendiente,it.importe) } }
        ventas.forEach { require(it.cantidad > 0 && it.precio >= 0); val subtotal=Math.multiplyExact(it.cantidad.toLong(),it.precio); when(it.pago) { Pago.EFECTIVO -> efectivo=Math.addExact(efectivo,subtotal); Pago.TARJETA -> tarjeta=Math.addExact(tarjeta,subtotal); Pago.SIN_CLASIFICAR -> pendiente=Math.addExact(pendiente,subtotal) } }
        require(efectivoCierre == null || efectivoCierre >= 0); require(tarjetaCierre == null || tarjetaCierre >= 0)
        return Conciliacion(efectivo,tarjeta,pendiente,efectivoCierre,tarjetaCierre)
    }
}
