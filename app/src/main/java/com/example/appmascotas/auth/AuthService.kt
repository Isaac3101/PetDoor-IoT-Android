package com.example.appmascotas.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration

/** Perfil de un usuario de la app. Vive en Firestore: colección "users", documento = UID de Authentication. */
data class UserProfile(
    val uid: String,
    val nombre: String,
    val email: String,
    val rol: String,
    val activo: Boolean
) {
    val isAdmin: Boolean get() = rol == ROL_ADMIN

    companion object {
        const val ROL_ADMIN = "admin"
        const val ROL_USUARIO = "usuario"
    }
}

/**
 * Login con Firebase Authentication + roles y gestión de usuarios en Firestore.
 *
 *  - Solo entra quien el administrador creó (no hay registro libre).
 *  - Al iniciar sesión se lee users/<uid>: si no existe o está desactivado, se cierra la sesión.
 *  - El administrador puede agregar, editar y eliminar usuarios (CRUD).
 *  - Para crear cuentas sin cerrar la sesión del admin se usa una segunda instancia de Firebase.
 *
 * Las reglas de Firestore (firestore.rules) hacen cumplir los permisos en el servidor.
 */
class AuthService(private val context: Context) {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val users get() = db.collection("users")

    val guard = LoginGuard()

    fun isSignedIn(): Boolean = auth.currentUser != null
    fun currentUid(): String? = auth.currentUser?.uid

    // ---------------- Sesión ----------------

    fun login(email: String, password: String, done: (UserProfile?, String?) -> Unit) {
        val wait = guard.remainingMs()
        if (wait > 0) {
            done(null, "Demasiados intentos fallidos. Espera ${(wait + 999) / 1000} s")
            return
        }
        Validators.email(email)?.let { done(null, it); return }
        if (password.isEmpty() || password.length > 128) {
            done(null, "Ingresa tu contraseña")
            return
        }
        auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { t ->
            if (!t.isSuccessful) {
                val e = t.exception
                if (e is FirebaseAuthInvalidUserException || e is FirebaseAuthInvalidCredentialsException) guard.fail()
                done(null, authError(e))
                return@addOnCompleteListener
            }
            guard.ok()
            val uid = t.result.user?.uid
            if (uid == null) {
                done(null, "No se pudo iniciar sesión")
                return@addOnCompleteListener
            }
            users.document(uid).get().addOnCompleteListener { r ->
                if (!r.isSuccessful) {
                    auth.signOut()
                    done(null, "No se pudo leer tu perfil. Revisa tu conexión a Internet")
                    return@addOnCompleteListener
                }
                val p = parse(r.result)
                when {
                    p == null -> {
                        auth.signOut()
                        done(null, "Tu cuenta no tiene perfil. Pide al administrador que la registre")
                    }
                    !p.activo -> {
                        auth.signOut()
                        done(null, "Tu cuenta está desactivada. Habla con el administrador")
                    }
                    else -> done(p, null)
                }
            }
        }
    }

    fun logout() {
        auth.signOut()
    }

    /**
     * Restaura la sesión que Firebase conserva en el celular. Devuelve el perfil si sigue siendo válido;
     * si el perfil no existe o está desactivado cierra la sesión. Sin Internet no decide nada (null, null).
     */
    fun restore(done: (UserProfile?, String?) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            done(null, null)
            return
        }
        users.document(uid).get().addOnCompleteListener { r ->
            if (!r.isSuccessful) {
                done(null, null)
                return@addOnCompleteListener
            }
            val p = parse(r.result)
            if (p == null || !p.activo) {
                auth.signOut()
                done(null, "Tu cuenta ya no tiene acceso")
            } else done(p, null)
        }
    }

    /** Vigila el perfil propio: si el admin te desactiva, te elimina o te cambia el rol, la app reacciona. */
    fun listenOwnProfile(uid: String, onChange: (UserProfile?) -> Unit): ListenerRegistration =
        users.document(uid).addSnapshotListener { snap, e ->
            if (e == null && snap != null && !snap.metadata.isFromCache) onChange(parse(snap))
        }

    // ---------------- CRUD de usuarios (solo admin) ----------------

    /** Escucha la lista de usuarios en tiempo real. Devuelve el registro para poder cancelarlo. */
    fun listenUsers(onChange: (List<UserProfile>) -> Unit, onError: (String) -> Unit): ListenerRegistration =
        users.addSnapshotListener { snap, e ->
            if (e != null) {
                onError(firestoreError(e))
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { parse(it) }.orEmpty()
                .sortedBy { it.nombre.lowercase() }
            onChange(list)
        }

    /** AGREGAR: crea la cuenta en Authentication y su perfil en Firestore. done(null) = éxito. */
    fun createUser(email: String, nombre: String, rol: String, password: String, done: (String?) -> Unit) {
        (Validators.email(email) ?: Validators.name(nombre) ?: Validators.password(password)
            ?: if (!Validators.role(rol)) "Rol no válido" else null)?.let { done(it); return }
        val sec = secondaryAuth()
        sec.createUserWithEmailAndPassword(email.trim(), password).addOnCompleteListener { t ->
            if (!t.isSuccessful) {
                done(authError(t.exception))
                return@addOnCompleteListener
            }
            val uid = t.result.user?.uid
            sec.signOut()                      // la sesión del admin (instancia principal) no se toca
            if (uid == null) {
                done("No se pudo crear la cuenta")
                return@addOnCompleteListener
            }
            val data = hashMapOf(
                "nombre" to Validators.clean(nombre.trim(), Validators.NAME_MAX),
                "email" to email.trim().lowercase(),
                "rol" to rol,
                "activo" to true
            )
            users.document(uid).set(data).addOnCompleteListener { w ->
                done(if (w.isSuccessful) null else "La cuenta se creó pero no se pudo guardar el perfil: ${firestoreError(w.exception)}")
            }
        }
    }

    /** EDITAR: nombre, rol y estado (activo/desactivado). */
    fun updateUser(uid: String, nombre: String, rol: String, activo: Boolean, done: (String?) -> Unit) {
        (Validators.name(nombre) ?: if (!Validators.role(rol)) "Rol no válido" else null)?.let { done(it); return }
        if (uid == currentUid() && (rol != UserProfile.ROL_ADMIN || !activo)) {
            done("No puedes quitarte el rol de admin ni desactivarte a ti mismo")
            return
        }
        users.document(uid)
            .update(mapOf("nombre" to Validators.clean(nombre.trim(), Validators.NAME_MAX), "rol" to rol, "activo" to activo))
            .addOnCompleteListener { done(if (it.isSuccessful) null else firestoreError(it.exception)) }
    }

    /**
     * ELIMINAR: borra el perfil. Sin perfil la app le niega la entrada.
     * (Borrar la cuenta de Authentication de otra persona no se puede hacer desde el celular;
     * queda sin acceso y se puede limpiar desde la consola de Firebase.)
     */
    fun deleteUser(uid: String, done: (String?) -> Unit) {
        if (uid == currentUid()) {
            done("No puedes eliminar tu propia cuenta")
            return
        }
        users.document(uid).delete()
            .addOnCompleteListener { done(if (it.isSuccessful) null else firestoreError(it.exception)) }
    }

    /** El admin no puede fijar la contraseña de otro: se le envía un correo para que la cambie. */
    fun sendPasswordReset(email: String, done: (String?) -> Unit) {
        Validators.email(email)?.let { done(it); return }
        auth.sendPasswordResetEmail(email.trim())
            .addOnCompleteListener { done(if (it.isSuccessful) null else authError(it.exception)) }
    }

    // ---------------- Internos ----------------

    private fun secondaryAuth(): FirebaseAuth {
        val name = "creador-de-usuarios"
        val app = try {
            FirebaseApp.getInstance(name)
        } catch (e: IllegalStateException) {
            FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, name)
        }
        return FirebaseAuth.getInstance(app)
    }

    private fun parse(d: com.google.firebase.firestore.DocumentSnapshot?): UserProfile? {
        if (d == null || !d.exists()) return null
        return UserProfile(
            uid = d.id,
            nombre = d.getString("nombre").orEmpty(),
            email = d.getString("email").orEmpty(),
            rol = d.getString("rol") ?: UserProfile.ROL_USUARIO,
            activo = d.getBoolean("activo") ?: false
        )
    }

    private fun authError(e: Exception?): String = when (e) {
        is FirebaseAuthInvalidUserException,
        is FirebaseAuthInvalidCredentialsException -> "Correo o contraseña incorrectos"
        is FirebaseAuthUserCollisionException -> "Ese correo ya tiene una cuenta"
        is FirebaseAuthWeakPasswordException -> "La contraseña es muy débil (mínimo 6 caracteres)"
        is FirebaseTooManyRequestsException -> "Demasiados intentos. Espera unos minutos"
        is FirebaseNetworkException -> "Sin Internet"
        else -> e?.message ?: "Error desconocido"
    }

    private fun firestoreError(e: Exception?): String =
        if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
            "Sin permisos: solo el administrador puede hacer esto"
        else e?.message ?: "Error desconocido"
}
