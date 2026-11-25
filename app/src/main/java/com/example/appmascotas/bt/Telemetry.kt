package com.example.appmascotas.bt

/** Estado del enlace con el Arduino (lo que muestra el recuadro de conexión). */
enum class LinkState { DESCONECTADO, CONECTANDO, CONECTADO, RECONECTANDO, ERROR }

/**
 * Telemetría que envía el Arduino (ya descifrada y verificada):
 *   "D:23,P:1,M:A,T:1000,Q:1700000000"
 *   D = distancia en cm (999 = sin eco) | P = puerta (1 abierta, 0 cerrada)
 *   M = modo (A automático, M manual)    | T = periodo de telemetría en ms
 *   Q = último número de secuencia válido recibido por el Arduino
 */
data class Telemetry(
    val distanceCm: Int,
    val doorOpen: Boolean,
    val autoMode: Boolean,
    val periodMs: Long,
    val lastSeq: Long
) {
    companion object {
        fun parse(payload: String): Telemetry? {
            if (!payload.startsWith("D:")) return null
            val map = HashMap<String, String>()
            for (part in payload.split(',')) {
                val i = part.indexOf(':')
                if (i <= 0) return null
                map[part.substring(0, i)] = part.substring(i + 1)
            }
            // Aunque la trama venga autenticada, se validan los rangos: un valor absurdo se descarta.
            val d = map["D"]?.toIntOrNull()?.takeIf { it in 0..999 } ?: return null
            val p = map["P"]?.takeIf { it == "0" || it == "1" } ?: return null
            val m = map["M"]?.takeIf { it == "A" || it == "M" } ?: return null
            val period = (map["T"]?.toLongOrNull() ?: 1000L).coerceIn(500L, 60_000L)
            val q = (map["Q"]?.toLongOrNull() ?: 0L).coerceIn(0L, 0xFFFFFFFFL)
            return Telemetry(
                distanceCm = d,
                doorOpen = p == "1",
                autoMode = m == "A",
                periodMs = period,
                lastSeq = q
            )
        }
    }
}
