package com.example.appmascotas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DashboardScreen(usuario: String, onLogout: () -> Unit) {
    var estadoPuerta by remember { mutableStateOf("CERRADA") }
    var distanciaSensor by remember { mutableStateOf(12) } // Distancia simulada en cm
    var historial by remember { mutableStateOf(listOf<String>()) }

    fun agregarEvento(mensaje: String) {
        historial = listOf(mensaje) + historial
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Cabecera con botón de salida mejorado
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hola, $usuario",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedButton(
                onClick = onLogout,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
            ) {
                Text("Salir")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Tarjeta con Estado de la Puerta y Sensor
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Puerta: $estadoPuerta", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Sensor HC-SR04: $distanciaSensor cm", fontSize = 16.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Botones de Control (Comandos Arduino '1', '0', 'A')
        Text("Controles Remotos", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    estadoPuerta = "ABIERTA"
                    agregarEvento("Comando '1': Puerta Abierta")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Abrir ('1')")
            }

            Button(
                onClick = {
                    estadoPuerta = "CERRADA"
                    agregarEvento("Comando '0': Puerta Cerrada")
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Cerrar ('0')")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                estadoPuerta = "AUTOMÁTICO"
                agregarEvento("Comando 'A': Modo Automático")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Modo Automático ('A')")
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Historial de Registros
        Text("Historial de Eventos", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(historial) { evento ->
                Text("• $evento", fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}