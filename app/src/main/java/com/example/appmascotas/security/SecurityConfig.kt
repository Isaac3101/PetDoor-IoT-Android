package com.example.appmascotas.security

/**
 * Claves compartidas entre la app y el sketch de Arduino (archivo secret.h).
 *
 * IMPORTANTE: deben ser IDÉNTICAS en ambos lados. Genera las tuyas propias
 * (16 bytes = 32 caracteres hex cada una) antes de la demostración final y
 * cámbialas en la app y en arduino/PetDoor/secret.h.
 *
 *  - ENC_KEY: cifra el contenido (AES-128 en modo CTR)  -> confidencialidad
 *  - MAC_KEY: firma cada trama (HMAC-SHA256 truncado)   -> integridad y autenticidad
 */
object SecurityConfig {
    const val ENC_KEY_HEX = "7bc1d0a0d7d6290a5835cb0373080bba"
    const val MAC_KEY_HEX = "d46db97efb9685333216f8b902b48430"

    /** Nombre (o parte del nombre) del módulo Bluetooth a buscar entre los dispositivos vinculados. */
    const val HC05_NAME_HINT = "HC-05"
}
