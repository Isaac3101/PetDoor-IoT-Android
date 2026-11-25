package com.example.appmascotas

import com.example.appmascotas.auth.LoginGuard
import com.example.appmascotas.auth.Validators
import com.example.appmascotas.bt.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    @Test
    fun correos() {
        assertNull(Validators.email("  admin@petdoor.cl "))
        assertNotNull(Validators.email(""))
        assertNotNull(Validators.email("sin-arroba"))
        assertNotNull(Validators.email("a@b"))
        assertNotNull(Validators.email("a b@c.cl"))
        assertNotNull(Validators.email("x".repeat(300) + "@c.cl"))
    }

    @Test
    fun nombres() {
        assertNull(Validators.name("María José"))
        assertNotNull(Validators.name("   "))
        assertNotNull(Validators.name("a".repeat(61)))
        assertNotNull(Validators.name("Hola\u0000mundo"))
    }

    @Test
    fun contrasenas() {
        assertNull(Validators.password("clave1234"))
        assertNotNull(Validators.password("corta1"))
        assertNotNull(Validators.password("sololetras"))
        assertNotNull(Validators.password("12345678"))
        assertNotNull(Validators.password("a1".repeat(40)))
    }

    @Test
    fun rolesYTextoLimpio() {
        assertTrue(Validators.role("admin") && Validators.role("usuario"))
        assertTrue(!Validators.role("root") && !Validators.role(""))
        assertEquals("abc", Validators.clean("a\nb\u0007c", 10))
        assertEquals("ab", Validators.clean("abcdef", 2))
    }

    @Test
    fun frenoDeIntentos() {
        var t = 1_000_000L
        val g = LoginGuard { t }
        repeat(4) { g.fail() }
        assertEquals(0L, g.remainingMs())
        g.fail()                                   // 5.º fallo: bloquea 30 s
        assertEquals(30_000L, g.remainingMs())
        t += 31_000
        assertEquals(0L, g.remainingMs())
        g.fail()                                   // 6.º fallo: 60 s
        assertEquals(60_000L, g.remainingMs())
        g.ok()
        assertEquals(0L, g.remainingMs())
    }

    @Test
    fun telemetriaConRangosInvalidosSeDescarta() {
        assertNotNull(Telemetry.parse("D:23,P:1,M:A,T:1000,Q:5"))
        assertNull(Telemetry.parse("D:-5,P:1,M:A,T:1000,Q:5"))
        assertNull(Telemetry.parse("D:5000,P:1,M:A,T:1000,Q:5"))
        assertNull(Telemetry.parse("D:23,P:7,M:A,T:1000,Q:5"))
        assertNull(Telemetry.parse("D:23,P:1,M:Z,T:1000,Q:5"))
        assertEquals(60_000L, Telemetry.parse("D:23,P:1,M:A,T:999999,Q:5")!!.periodMs)
    }
}
