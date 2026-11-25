package com.example.appmascotas.security

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Canal seguro App <-> Arduino sobre el puerto serie Bluetooth (HC-05).
 *
 * Cada mensaje viaja como una línea de texto ASCII:
 *
 *     E,<dir>,<seq>,<cifrado_hex>,<mac_hex>\n
 *
 *  - dir : 'A' = app -> Arduino, 'D' = Arduino -> app
 *  - seq : contador de 32 bits (anti-replay); el receptor solo acepta seq mayores
 *  - cifrado : payload (máx. 48 bytes) cifrado con AES-128-CTR
 *              IV = [dir][seq BE 4 bytes][7 bytes en 0][contador BE 4 bytes = 0]
 *  - mac : primeros 8 bytes de HMAC-SHA256(macKey, dir || seq || cifrado)
 *
 * Alineado con ISO/IEC 27400 (IoT): confidencialidad, integridad, autenticidad del
 * origen y protección contra repetición. El mismo formato se implementa en el sketch.
 */
class SecureProtocol(encKey: ByteArray, macKey: ByteArray) {

    class Frame(val seq: Long, val payload: String)

    private val encKeySpec = SecretKeySpec(encKey, "AES")
    private val macKeySpec = SecretKeySpec(macKey, "HmacSHA256")

    constructor() : this(
        Hex.decode(SecurityConfig.ENC_KEY_HEX)!!,
        Hex.decode(SecurityConfig.MAC_KEY_HEX)!!
    )

    fun encode(dir: Char, seq: Long, payload: String): String {
        val plain = payload.toByteArray(Charsets.US_ASCII)
        require(plain.size <= MAX_PAYLOAD) { "Payload demasiado largo" }
        val cipher = crypt(dir, seq, plain, Cipher.ENCRYPT_MODE)
        val mac = mac(dir, seq, cipher)
        return "E,$dir,$seq,${Hex.encode(cipher)},${Hex.encode(mac)}"
    }

    /** Devuelve null si la trama está mal formada, el origen no coincide o falla la firma. */
    fun decode(line: String, expectedDir: Char): Frame? {
        val parts = line.trim().split(',')
        if (parts.size != 5 || parts[0] != "E" || parts[1] != expectedDir.toString()) return null
        val seq = parts[2].toLongOrNull() ?: return null
        if (seq < 0 || seq > 0xFFFFFFFFL) return null
        if (parts[3].length > MAX_PAYLOAD * 2) return null
        val cipher = Hex.decode(parts[3]) ?: return null
        val macRx = Hex.decode(parts[4]) ?: return null
        if (macRx.size != MAC_LEN) return null
        val macOk = MessageDigest.isEqual(mac(expectedDir, seq, cipher), macRx)
        if (!macOk) return null
        val plain = crypt(expectedDir, seq, cipher, Cipher.DECRYPT_MODE)
        return Frame(seq, String(plain, Charsets.US_ASCII))
    }

    /** Firma genérica (para autenticar órdenes que viajan por la nube). */
    fun sign(data: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(macKeySpec)
        return Hex.encode(m.doFinal(data.toByteArray(Charsets.UTF_8)).copyOf(16))
    }

    fun verifySign(data: String, signature: String): Boolean {
        val expected = sign(data).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, signature.lowercase().toByteArray(Charsets.US_ASCII))
    }

    private fun crypt(dir: Char, seq: Long, data: ByteArray, mode: Int): ByteArray {
        val iv = ByteArray(16)
        iv[0] = dir.code.toByte()
        iv[1] = (seq shr 24).toByte()
        iv[2] = (seq shr 16).toByte()
        iv[3] = (seq shr 8).toByte()
        iv[4] = seq.toByte()
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(mode, encKeySpec, IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun mac(dir: Char, seq: Long, cipher: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(macKeySpec)
        m.update(dir.code.toByte())
        m.update(byteArrayOf((seq shr 24).toByte(), (seq shr 16).toByte(), (seq shr 8).toByte(), seq.toByte()))
        m.update(cipher)
        return m.doFinal().copyOf(MAC_LEN)
    }

    companion object {
        const val DIR_APP = 'A'
        const val DIR_DEVICE = 'D'
        const val MAX_PAYLOAD = 48
        private const val MAC_LEN = 8
    }
}
