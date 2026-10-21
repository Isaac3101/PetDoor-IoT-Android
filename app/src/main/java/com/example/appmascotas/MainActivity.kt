package com.example.appmascotas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.appmascotas.ui.theme.AppMascotasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppMascotasTheme {
                var usuarioLogueado by remember { mutableStateOf("") }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (usuarioLogueado.isEmpty()) {
                            LoginScreen(
                                onLoginSuccess = { usuario -> usuarioLogueado = usuario }
                            )
                        } else {
                            DashboardScreen(
                                usuario = usuarioLogueado,
                                onLogout = { usuarioLogueado = "" }
                            )
                        }
                    }
                }
            }
        }
    }
}