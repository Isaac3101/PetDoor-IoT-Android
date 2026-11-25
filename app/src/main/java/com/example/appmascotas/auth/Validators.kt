package com.example.appmascotas.auth

/** Validaciones de entrada sin dependencias de Android (se prueban en JVM). Devuelven null si es válido. */
object Validators {
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,24}$")
    const val NAME_MAX = 60
    const val PASS_MIN = 8
    const val PASS_MAX = 64

    fun email(raw: String): String? {
        val s = raw.trim()
        return when {
            s.isEmpty() -> "Escribe el correo"
            s.length > 254 || !EMAIL.matches(s) -> "El correo no es válido"
            else -> null
        }
    }

    fun name(raw: String): String? {
        val s = raw.trim()
        return when {
            s.isEmpty() -> "Escribe el nombre"
            s.length > NAME_MAX -> "El nombre es muy largo (máximo $NAME_MAX caracteres)"
            s.any { it.isISOControl() } -> "El nombre tiene caracteres no permitidos"
            else -> null
        }
    }

    /** Política para cuentas nuevas: 8 a 64 caracteres, con al menos una letra y un número. */
    fun password(p: String): String? = when {
        p.length < PASS_MIN -> "La contraseña debe tener al menos $PASS_MIN caracteres"
        p.length > PASS_MAX -> "La contraseña es muy larga (máximo $PASS_MAX)"
        p.none { it.isLetter() } || p.none { it.isDigit() } -> "La contraseña debe mezclar letras y números"
        else -> null
    }

    fun role(r: String): Boolean = r == UserProfile.ROL_ADMIN || r == UserProfile.ROL_USUARIO

    /** Recorta y quita caracteres de control antes de guardar texto libre. */
    fun clean(s: String, max: Int): String = s.filter { !it.isISOControl() }.take(max)
}
