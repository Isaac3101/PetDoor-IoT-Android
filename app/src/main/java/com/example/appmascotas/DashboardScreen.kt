package com.example.appmascotas

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appmascotas.bt.LinkState
import com.example.appmascotas.cloud.CloudStatus
import com.example.appmascotas.data.EventRow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Verde = Color(0xFF2E7D32)
private val Ambar = Color(0xFFF9A825)
private val Rojo = Color(0xFFC62828)
private val Gris = Color(0xFF757575)

private const val GATEWAY_STALE_MS = 45_000L

@Composable
fun DashboardScreen(controller: PetDoorController, usuario: String, onUsers: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val c = controller

    // Reloj que se actualiza cada segundo ("hace 3 s", cuenta regresiva, etc.)
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            ahora = System.currentTimeMillis()
        }
    }

    var mostrarSelector by remember { mutableStateOf(false) }

    // ---- Conexión: permisos, Bluetooth encendido y selección de dispositivo ----
    fun intentarConectar() {
        when {
            !c.isBluetoothSupported() -> c.message = "Este celular no tiene Bluetooth"
            !c.hasBluetoothPermission() -> c.message = "Falta el permiso de Bluetooth"
            !c.isBluetoothOn() -> c.message = "Enciende el Bluetooth y vuelve a intentar"
            else -> if (!c.connectPreferred()) mostrarSelector = true
        }
    }

    val lanzadorPermisos = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { intentarConectar() }

    val lanzadorEncender = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { intentarConectar() }

    fun conectar() {
        if (Build.VERSION.SDK_INT >= 31 && !c.hasBluetoothPermission()) {
            // Android 12+: BLUETOOTH_CONNECT se pide en tiempo de ejecución (solo ese, nada de ubicación).
            lanzadorPermisos.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        if (c.isBluetoothSupported() && c.hasBluetoothPermission() && !c.isBluetoothOn()) {
            lanzadorEncender.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        intentarConectar()
    }

    // ---- Datos a mostrar según el modo (directo por Bluetooth / remoto por la nube) ----
    val remoto = c.remoteMode
    val rs = c.remoteState
    val gatewayVivo = rs != null && rs.gatewayConnected && ahora - rs.updatedMs < GATEWAY_STALE_MS
    val enlaceOk = c.linkState == LinkState.CONECTADO
    val t = c.telemetry

    val hayDatos = if (remoto) gatewayVivo else enlaceOk && t != null
    val puertaAbierta = if (remoto) rs?.doorOpen == true else t?.doorOpen == true
    val modoAuto = if (remoto) rs?.autoMode == true else t?.autoMode == true
    val distancia = if (remoto) rs?.distanceCm else t?.distanceCm
    val puedeControlar = if (remoto) {
        gatewayVivo && (c.cloudStatus == CloudStatus.SINCRONIZADA || c.cloudStatus == CloudStatus.PENDIENTES)
    } else enlaceOk

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Cabecera ----
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hola, $usuario", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (c.profile?.isAdmin == true) "Administrador" else "Usuario",
                        fontSize = 12.sp, color = Gris
                    )
                }
                if (c.profile?.isAdmin == true) {
                    OutlinedButton(onClick = onUsers) { Text("Usuarios") }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(
                    onClick = onLogout,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Rojo)
                ) { Text("Salir") }
            }
        }

        // ---- Selector de modo ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !remoto,
                    onClick = { c.switchRemoteMode(false) },
                    label = { Text("Directo (Bluetooth)") }
                )
                FilterChip(
                    selected = remoto,
                    onClick = { c.switchRemoteMode(true) },
                    label = { Text("Remoto (nube)") }
                )
            }
        }

        // ---- RECUADRO DE ESTADO DE CONEXIÓN ----
        item {
            TarjetaConexion(
                c = c,
                ahora = ahora,
                gatewayVivo = gatewayVivo,
                onConectar = { conectar() },
                onElegir = { mostrarSelector = true }
            )
        }

        // ---- Estado de la puerta y sensor ----
        item {
            TarjetaPuerta(
                hayDatos = hayDatos,
                abierta = puertaAbierta,
                auto = modoAuto,
                distanciaCm = distancia
            )
        }

        // ---- Controles ----
        item {
            Text("Controles Remotos", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { c.sendCommand("1") },
                    enabled = puedeControlar,
                    modifier = Modifier.weight(1f)
                ) { Text("Abrir ('1')") }
                Button(
                    onClick = { c.sendCommand("0") },
                    enabled = puedeControlar,
                    modifier = Modifier.weight(1f)
                ) { Text("Cerrar ('0')") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { c.sendCommand("A") },
                enabled = puedeControlar,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Modo Automático ('A')") }
            if (!puedeControlar) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Plan de contingencia: sin enlace los botones se bloquean y la puerta sigue funcionando sola " +
                        "con su sensor (modo automático local). Al volver el enlace se reconecta automáticamente.",
                    fontSize = 12.sp,
                    color = Gris
                )
            }
        }

        // ---- Historial (bitácora) ----
        item {
            Text("Historial de Eventos", fontWeight = FontWeight.Bold)
            if (remoto) {
                Text("Eventos sincronizados en la nube", fontSize = 12.sp, color = Gris)
            } else {
                Text("Guardado en este celular (SQLite) y sincronizado cuando hay Internet", fontSize = 12.sp, color = Gris)
            }
        }
        val lista = if (remoto) c.remoteEvents else c.events
        if (lista.isEmpty()) {
            item { Text("Aún no hay eventos.", fontSize = 14.sp, color = Gris) }
        }
        items(lista, key = { it.id }) { ev -> FilaEvento(ev) }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    if (mostrarSelector) {
        SelectorDispositivo(
            c = c,
            onElegido = { d ->
                mostrarSelector = false
                c.connectTo(d)
            },
            onAjustes = {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            },
            onCerrar = { mostrarSelector = false }
        )
    }
}

// =====================================================================
//  Recuadro de estado de conexión con el Arduino
// =====================================================================
@Composable
private fun TarjetaConexion(
    c: PetDoorController,
    ahora: Long,
    gatewayVivo: Boolean,
    onConectar: () -> Unit,
    onElegir: () -> Unit
) {
    val remoto = c.remoteMode
    val rs = c.remoteState

    // Color, título y detalle según el estado
    val color: Color
    val titulo: String
    val detalle: String
    if (!remoto) {
        when (c.linkState) {
            LinkState.CONECTADO -> { color = Verde; titulo = "CONECTADO al Arduino" }
            LinkState.CONECTANDO -> { color = Ambar; titulo = "Conectando…" }
            LinkState.RECONECTANDO -> {
                color = Ambar
                titulo = "Reconectando… (intento ${c.linkAttempt})"
            }
            LinkState.ERROR -> { color = Rojo; titulo = "ERROR de conexión" }
            LinkState.DESCONECTADO -> { color = Rojo; titulo = "DESCONECTADO del Arduino" }
        }
        detalle = c.linkDetail
    } else {
        when {
            gatewayVivo -> {
                color = Verde
                titulo = "REMOTO · Arduino conectado"
                detalle = "Otro celular (${rs?.gateway}) está enlazado al Arduino por Bluetooth."
            }
            rs == null -> {
                color = Ambar
                titulo = "REMOTO · Esperando datos de la nube…"
                detalle = c.cloudDetail.ifEmpty { "Consultando estado…" }
            }
            else -> {
                color = Rojo
                titulo = "REMOTO · Arduino SIN conexión"
                detalle = "Ningún celular está conectado al Arduino (último dato hace ${hace(ahora, rs.updatedMs)})."
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(color, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(titulo, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Text(detalle, fontSize = 13.sp, color = Gris)

            if (!remoto) {
                Text(
                    "Módulo: " + c.deviceLabel.ifEmpty { "sin dispositivo seleccionado" },
                    fontSize = 13.sp
                )
                if (c.linkState == LinkState.CONECTADO) {
                    Text(
                        "Último dato: hace ${hace(ahora, c.lastDataMs)} · canal cifrado AES-128 + firma HMAC ✓",
                        fontSize = 13.sp
                    )
                } else if (c.lastDataMs > 0) {
                    Text("Último dato recibido: hace ${hace(ahora, c.lastDataMs)}", fontSize = 13.sp)
                }
                if (c.rejectedFrames > 0) {
                    Text(
                        "Tramas rechazadas por seguridad: ${c.rejectedFrames}",
                        fontSize = 13.sp,
                        color = Rojo
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.linkState == LinkState.DESCONECTADO || c.linkState == LinkState.ERROR) {
                        Button(onClick = onConectar) { Text("Conectar") }
                        OutlinedButton(onClick = onElegir) { Text("Elegir dispositivo") }
                    } else {
                        OutlinedButton(onClick = { c.disconnect() }) { Text("Desconectar") }
                    }
                }
            }

            HorizontalDivider()
            FilaNube(c, ahora)
        }
    }
}

@Composable
private fun FilaNube(c: PetDoorController, ahora: Long) {
    val color: Color
    val texto: String
    when (c.cloudStatus) {
        CloudStatus.SINCRONIZADA -> {
            color = Verde
            texto = "Nube: sincronizada" + if (c.lastSyncMs > 0) " (hace ${hace(ahora, c.lastSyncMs)})" else ""
        }
        CloudStatus.PENDIENTES -> { color = Ambar; texto = "Nube: ${c.pendingCount} eventos por sincronizar" }
        CloudStatus.SIN_RED -> {
            color = Ambar
            texto = "Sin Internet · ${c.pendingCount} eventos guardados en el celular"
        }
        CloudStatus.SIN_SESION -> {
            color = Gris
            texto = "Sin sesión en la nube · ${c.pendingCount} eventos guardados en el celular"
        }
        CloudStatus.ERROR -> { color = Rojo; texto = "Nube: error · ${c.pendingCount} pendientes (${c.cloudDetail})" }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(texto, fontSize = 13.sp)
    }
}

// =====================================================================
//  Puerta + sensor ultrasónico
// =====================================================================
@Composable
private fun TarjetaPuerta(hayDatos: Boolean, abierta: Boolean, auto: Boolean, distanciaCm: Int?) {
    val color = when {
        !hayDatos -> Gris
        abierta -> Verde
        else -> Rojo
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Puerta: " + when {
                    !hayDatos -> "SIN DATOS"
                    abierta -> "ABIERTA"
                    else -> "CERRADA"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(6.dp))
            val d = distanciaCm
            Text(
                "Sensor HC-SR04: " + if (!hayDatos || d == null) "—" else PetDoorController.distanceText(d),
                fontSize = 16.sp,
                color = Gris
            )
            if (hayDatos && d != null && d < 400) {
                Spacer(modifier = Modifier.height(6.dp))
                // Barra de proximidad: llena cuando algo está muy cerca (0 cm) y vacía a 100 cm o más.
                LinearProgressIndicator(
                    progress = { (1f - d / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = color
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Modo: " + when {
                    !hayDatos -> "—"
                    auto -> "Automático"
                    else -> "Manual"
                },
                fontSize = 14.sp
            )
        }
    }
}

// =====================================================================
//  Historial
// =====================================================================
@Composable
private fun FilaEvento(e: EventRow) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }
    val critico = e.type == "SEGURIDAD" || e.type == "CMD_FALLIDO" || e.type == "ALERTA"
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt.format(Date(e.ts)), fontSize = 12.sp, color = Gris)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                e.type,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (critico) Rojo else Color.Unspecified
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("${e.source} · ${e.user}", fontSize = 11.sp, color = Gris)
            if (!e.synced) {
                Spacer(modifier = Modifier.width(8.dp))
                Text("⏳", fontSize = 11.sp)
            }
        }
        Text(e.detail, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        HorizontalDivider()
    }
}

// =====================================================================
//  Diálogo para elegir el HC-05 entre los dispositivos vinculados
// =====================================================================
@Composable
private fun SelectorDispositivo(
    c: PetDoorController,
    onElegido: (android.bluetooth.BluetoothDevice) -> Unit,
    onAjustes: () -> Unit,
    onCerrar: () -> Unit
) {
    val dispositivos = remember { c.bondedDevices() }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Elige el módulo del Arduino") },
        text = {
            if (dispositivos.isEmpty()) {
                Text(
                    "No hay dispositivos Bluetooth vinculados. Vincula el HC-05 desde Ajustes de Bluetooth " +
                        "(PIN habitual: 1234 o 0000) y vuelve a esta pantalla."
                )
            } else {
                Column {
                    dispositivos.forEach { d ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onElegido(d) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(c.nameOf(d), fontWeight = FontWeight.Bold)
                            Text(d.address, fontSize = 12.sp, color = Gris)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAjustes) { Text("Ajustes Bluetooth") } },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } }
    )
}

private fun hace(ahora: Long, ts: Long): String {
    if (ts <= 0) return "—"
    val s = ((ahora - ts) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h"
    }
}
