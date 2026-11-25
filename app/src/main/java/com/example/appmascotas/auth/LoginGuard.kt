package com.example.appmascotas.auth

/**
 * Freno a los intentos de acceso: tras 5 fallos seguidos bloquea 30 s, y el bloqueo se duplica
 * (hasta 5 min) si se sigue fallando. Complementa el límite que ya aplica Firebase en el servidor.
 */
class LoginGuard(private val now: () -> Long = System::currentTimeMillis) {
    private var failures = 0
    private var lockedUntil = 0L

    @Synchronized
    fun remainingMs(): Long = (lockedUntil - now()).coerceAtLeast(0L)

    @Synchronized
    fun fail() {
        failures++
        if (failures >= MAX_FAILURES) {
            val level = (failures - MAX_FAILURES).coerceAtMost(4)
            lockedUntil = now() + minOf(BASE_LOCK_MS shl level, MAX_LOCK_MS)
        }
    }

    @Synchronized
    fun ok() {
        failures = 0
        lockedUntil = 0L
    }

    companion object {
        const val MAX_FAILURES = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 300_000L
    }
}
