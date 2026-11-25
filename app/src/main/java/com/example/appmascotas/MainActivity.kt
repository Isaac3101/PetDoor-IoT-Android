package com.example.appmascotas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.appmascotas.ui.theme.AppMascotasTheme

class MainActivity : ComponentActivity() {

    private val controller: PetDoorController
        get() = (application as PetDoorApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = controller
        setContent {
            AppMascotasTheme {
                val snackbar = remember { SnackbarHostState() }
                // La sesión vive en el controlador: girar la pantalla no cierra sesión ni corta Bluetooth.
                val usuario = c.currentUser
                var verUsuarios by remember { mutableStateOf(false) }

                LaunchedEffect(c.message) {
                    c.message?.let {
                        snackbar.showSnackbar(it)
                        c.message = null
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (usuario.isEmpty()) {
                            LoginScreen(
                                auth = c.authService,
                                onLoginSuccess = { u -> c.onLogin(u) }
                            )
                        } else {
                            if (verUsuarios && c.profile?.isAdmin == true) {
                                UsersScreen(controller = c, onBack = { verUsuarios = false })
                            } else {
                                DashboardScreen(
                                    controller = c,
                                    usuario = usuario,
                                    onUsers = { verUsuarios = true },
                                    onLogout = { verUsuarios = false; c.onLogout() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        // Ahorro de energía: en segundo plano se pide menos telemetría al Arduino.
        controller.onAppForeground(false)
    }
}
