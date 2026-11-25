package com.example.appmascotas

import com.example.appmascotas.bt.Telemetry
import com.example.appmascotas.security.SecureProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la comunicación segura (ISO/IEC 27400) y del almacenamiento de contraseñas.
 * Los "vectores" fueron generados por el código del sketch de Arduino (compilado en PC con la
 * librería Crypto) y por una implementación de referencia independiente en Python:
 * si la app los descifra, app y Arduino hablan el mismo protocolo.
 */
class SecureProtocolTest {
    private val sp = SecureProtocol()

    @Test
    fun descifraOrdenDeLaApp() {
        val f = sp.decode("E,A,1700000000,b8,b2f3d79f6a5ae1df", SecureProtocol.DIR_APP)
        assertNotNull(f)
        assertEquals(1700000000L, f!!.seq)
        assertEquals("1", f.payload)
    }

    @Test
    fun descifraTelemetriaDelArduino() {
        val f = sp.decode(
            "E,D,5,20211664e27ae376b119bffc5b6fb4ac4fb2cb,8b14d6be4ad55685",
            SecureProtocol.DIR_DEVICE
        )
        assertNotNull(f)
        assertEquals("D:23,P:1,M:A,T:1000", f!!.payload)
    }

    @Test
    fun descifraMensajeLargoDeTresBloques() {
        val f = sp.decode(
            "E,D,7,5d764dc8489103d8f3bfaa2ad8aba5f93940a68c04fd1022a75381d48509a4b868a80b1e59a2ed68c79ebd80a630eff3,e611a735fd732caf",
            SecureProtocol.DIR_DEVICE
        )
        assertEquals("Q:4294967295,D:999,P:1,M:A,T:60000,PADDING-PADDI", f?.payload)
    }

    @Test
    fun ida_y_vuelta() {
        val line = sp.encode(SecureProtocol.DIR_APP, 42, "T5000")
        assertEquals("T5000", sp.decode(line, SecureProtocol.DIR_APP)?.payload)
    }

    @Test
    fun rechazaTramaAlterada() {
        val ok = sp.encode(SecureProtocol.DIR_APP, 10, "1")
        val parts = ok.split(',').toMutableList()
        // cambia un dígito del cifrado: la firma ya no coincide
        parts[3] = if (parts[3][0] == '0') "1" + parts[3].substring(1) else "0" + parts[3].substring(1)
        assertNull(sp.decode(parts.joinToString(","), SecureProtocol.DIR_APP))
    }

    @Test
    fun rechazaMacAlterado() {
        val ok = sp.encode(SecureProtocol.DIR_APP, 10, "1")
        val bad = ok.dropLast(1) + (if (ok.last() == '0') "1" else "0")
        assertNull(sp.decode(bad, SecureProtocol.DIR_APP))
    }

    @Test
    fun rechazaDireccionEquivocada() {
        // Una orden de la app no se acepta como si viniera del Arduino (evita reflejar tramas)
        val orden = sp.encode(SecureProtocol.DIR_APP, 3, "1")
        assertNull(sp.decode(orden, SecureProtocol.DIR_DEVICE))
    }

    @Test
    fun rechazaBasura() {
        assertNull(sp.decode("", SecureProtocol.DIR_DEVICE))
        assertNull(sp.decode("hola", SecureProtocol.DIR_DEVICE))
        assertNull(sp.decode("E,D,x,zz,qq", SecureProtocol.DIR_DEVICE))
        assertNull(sp.decode("E,D,1,abc,0011223344556677", SecureProtocol.DIR_DEVICE)) // hex impar
    }

    @Test
    fun firmaDeOrdenesRemotas() {
        val s = sp.sign("1|1700000000|abc")
        assertTrue(sp.verifySign("1|1700000000|abc", s))
        assertFalse(sp.verifySign("0|1700000000|abc", s))
    }

    @Test
    fun interpretaTelemetria() {
        val t = Telemetry.parse("D:23,P:1,M:A,T:1000,Q:1700000000")
        assertNotNull(t)
        assertEquals(23, t!!.distanceCm)
        assertTrue(t.doorOpen)
        assertTrue(t.autoMode)
        assertEquals(1000L, t.periodMs)
        assertEquals(1700000000L, t.lastSeq)
        assertNull(Telemetry.parse("ACK:1"))
        assertNull(Telemetry.parse("D:abc,P:1,M:A"))
    }
}
