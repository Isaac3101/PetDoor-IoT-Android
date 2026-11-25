package com.example.appmascotas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appmascotas.auth.UserProfile
import com.example.appmascotas.auth.Validators

private val Rojo = Color(0xFFC62828)
private val Gris = Color(0xFF757575)
private val Verde = Color(0xFF2E7D32)

/**
 * Gestión de usuarios (solo administrador): AGREGAR, EDITAR y ELIMINAR.
 * La lista se actualiza en tiempo real desde Firestore. Los permisos también los exigen
 * las reglas de Firestore en el servidor, no solo esta pantalla.
 */
@Composable
fun UsersScreen(controller: PetDoorController, onBack: () -> Unit) {
    val auth = controller.authService
    val yo = controller.profile
    val usuarios = remember { mutableStateListOf<UserProfile>() }
    var errorLista by remember { mutableStateOf<String?>(null) }
    var agregando by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf<UserProfile?>(null) }
    var eliminando by remember { mutableStateOf<UserProfile?>(null) }

    // Siempre debe quedar al menos un administrador activo, o nadie podría gestionar la app.
    fun esUltimoAdmin(u: UserProfile) =
        u.isAdmin && u.activo && usuarios.count { it.isAdmin && it.activo } <= 1

    DisposableEffect(Unit) {
        val reg = auth.listenUsers(
            onChange = { lista ->
                usuarios.clear()
                usuarios.addAll(lista)
                errorLista = null
            },
            onError = { errorLista = it }
        )
        onDispose { reg.remove() }
    }

    if (agregando) {
        FormularioUsuario(
            titulo = "Agregar usuario", usuario = null, esYo = false,
            onCerrar = { agregando = false },
            onGuardar = { f, listo ->
                auth.createUser(f.email, f.nombre, f.rol, f.password) { err ->
                    listo(err)
                    if (err == null) {
                        agregando = false
                        controller.addEvent("USUARIO", "Usuario creado: ${f.email} (${f.rol})", "LOCAL")
                    }
                }
            }
        )
        return
    }
    editando?.let { u ->
        FormularioUsuario(
            titulo = "Editar usuario", usuario = u, esYo = u.uid == yo?.uid,
            onCerrar = { editando = null },
            onGuardar = { f, listo ->
                if (esUltimoAdmin(u) && (f.rol != UserProfile.ROL_ADMIN || !f.activo)) {
                    listo("Debe quedar al menos un administrador activo")
                    return@FormularioUsuario
                }
                auth.updateUser(u.uid, f.nombre, f.rol, f.activo) { err ->
                    listo(err)
                    if (err == null) {
                        editando = null
                        controller.addEvent("USUARIO", "Usuario editado: ${u.email} (${f.rol}, ${if (f.activo) "activo" else "desactivado"})", "LOCAL")
                    }
                }
            },
            onRestablecer = { listo ->
                auth.sendPasswordReset(u.email) { err ->
                    listo(err)
                    if (err == null) controller.message = "Se envió un correo a ${u.email} para cambiar la contraseña"
                }
            }
        )
        return
    }
    eliminando?.let { u ->
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Eliminar usuario", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("¿Eliminar a ${u.nombre.ifEmpty { u.email }}? Ya no podrá iniciar sesión.")
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { eliminando = null }) { Text("Cancelar") }
                Button(
                    onClick = {
                        if (esUltimoAdmin(u)) {
                            controller.message = "Debe quedar al menos un administrador activo"
                            eliminando = null
                            return@Button
                        }
                        auth.deleteUser(u.uid) { err ->
                            if (err == null) controller.addEvent("USUARIO", "Usuario eliminado: ${u.email}", "LOCAL")
                            else controller.message = err
                        }
                        eliminando = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Rojo)
                ) { Text("Eliminar") }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← Volver") }
            Button(onClick = { agregando = true }) { Text("+ Agregar usuario") }
        }
        Text("Usuarios de la app", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Solo ellos pueden iniciar sesión.", fontSize = 12.sp, color = Gris)
        Spacer(modifier = Modifier.height(12.dp))

        errorLista?.let { Text(it, color = Rojo, fontSize = 13.sp) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(usuarios, key = { it.uid }) { u ->
                val esYo = u.uid == yo?.uid
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                u.nombre.ifEmpty { "(sin nombre)" } + if (esYo) "  (tú)" else "",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                u.rol.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = if (u.isAdmin) Verde else Gris
                            )
                        }
                        Text(u.email, fontSize = 13.sp, color = Gris)
                        if (!u.activo) Text("Desactivado", fontSize = 12.sp, color = Rojo)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editando = u }) { Text("Editar") }
                            OutlinedButton(
                                onClick = { eliminando = u },
                                enabled = !esYo,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Rojo)
                            ) { Text("Eliminar") }
                        }
                    }
                }
            }
        }
    }
}

private class FormUsuario(
    val email: String, val nombre: String, val rol: String, val password: String, val activo: Boolean
)

@Composable
private fun FormularioUsuario(
    titulo: String,
    usuario: UserProfile?,
    esYo: Boolean,
    onCerrar: () -> Unit,
    onGuardar: (FormUsuario, (String?) -> Unit) -> Unit,
    onRestablecer: (((String?) -> Unit) -> Unit)? = null
) {
    val nuevo = usuario == null
    var email by remember { mutableStateOf(usuario?.email ?: "") }
    var nombre by remember { mutableStateOf(usuario?.nombre ?: "") }
    var password by remember { mutableStateOf("") }
    var rol by remember { mutableStateOf(usuario?.rol ?: UserProfile.ROL_USUARIO) }
    var activo by remember { mutableStateOf(usuario?.activo ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }

    fun guardar() {
        error = Validators.name(nombre)
            ?: if (nuevo) (Validators.email(email) ?: Validators.password(password)) else null
        if (error != null) return
        ocupado = true
        onGuardar(FormUsuario(email, nombre, rol, password, activo)) { err ->
            ocupado = false
            error = err
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(titulo, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = email, onValueChange = { email = it.take(254) },
            label = { Text("Correo") }, singleLine = true,
            enabled = nuevo && !ocupado,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = nombre, onValueChange = { nombre = it.take(Validators.NAME_MAX) },
            label = { Text("Nombre") }, singleLine = true, enabled = !ocupado,
            modifier = Modifier.fillMaxWidth()
        )
        if (nuevo) {
            OutlinedTextField(
                value = password, onValueChange = { password = it.take(Validators.PASS_MAX) },
                label = { Text("Contraseña inicial (8+, letras y números)") }, singleLine = true, enabled = !ocupado,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text("Rol", fontSize = 13.sp, color = Gris)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = rol == UserProfile.ROL_USUARIO,
                onClick = { if (!esYo) rol = UserProfile.ROL_USUARIO },
                label = { Text("Usuario") }
            )
            FilterChip(
                selected = rol == UserProfile.ROL_ADMIN,
                onClick = { rol = UserProfile.ROL_ADMIN },
                label = { Text("Admin") }
            )
        }
        if (!nuevo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Activo", modifier = Modifier.weight(1f))
                Switch(checked = activo, onCheckedChange = { if (!esYo) activo = it }, enabled = !esYo)
            }
            if (esYo) Text("No puedes quitarte el rol de admin ni desactivarte a ti mismo.", fontSize = 12.sp, color = Gris)
            if (onRestablecer != null) {
                TextButton(onClick = {
                    ocupado = true
                    onRestablecer { err -> ocupado = false; error = err }
                }, enabled = !ocupado) { Text("Enviar correo para cambiar contraseña") }
            }
        }
        error?.let { Text(it, color = Rojo, fontSize = 13.sp) }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCerrar, enabled = !ocupado) { Text("Cancelar") }
            Button(onClick = { guardar() }, enabled = !ocupado) { Text(if (ocupado) "Guardando…" else "Guardar") }
        }
    }
}
