package com.example.appmascotas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.appmascotas.auth.AuthService
import com.example.appmascotas.auth.UserProfile
import com.example.appmascotas.auth.Validators

/**
 * Pantalla de acceso con Firebase Authentication (correo y contraseña).
 * No hay registro libre: las cuentas las crea el administrador. Hasta iniciar sesión no se
 * habilita telemetría ni ninguna orden hacia la puerta.
 */
@Composable
fun LoginScreen(auth: AuthService, onLoginSuccess: (UserProfile) -> Unit) {
    var correo by remember { mutableStateOf("") }
    var clave by remember { mutableStateOf("") }
    var mensaje by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }

    fun ingresar() {
        if (ocupado) return
        if (correo.isBlank() || clave.isEmpty()) {
            mensaje = "Ingresa tu correo y contraseña"
            return
        }
        Validators.email(correo)?.let { mensaje = it; return }
        ocupado = true
        mensaje = ""
        auth.login(correo, clave) { perfil, error ->
            ocupado = false
            if (perfil != null) {
                clave = ""
                onLoginSuccess(perfil)
            } else {
                mensaje = error ?: "No se pudo iniciar sesión"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("PetDoor", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Acceso al Sistema", fontSize = 16.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = correo,
            onValueChange = { correo = it.take(254) },
            label = { Text("Correo") },
            singleLine = true,
            enabled = !ocupado,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = clave,
            onValueChange = { clave = it.take(128) },
            label = { Text("Contraseña") },
            singleLine = true,
            enabled = !ocupado,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        if (mensaje.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(mensaje, color = Color(0xFFC62828), fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { ingresar() },
            enabled = !ocupado,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (ocupado) "Verificando…" else "Ingresar")
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Solo pueden entrar las cuentas creadas por el administrador.",
            fontSize = 12.sp,
            color = Color.Gray
        )
    }
}
