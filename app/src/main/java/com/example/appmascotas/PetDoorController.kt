package com.example.appmascotas

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.appmascotas.bt.BluetoothLink
import com.example.appmascotas.bt.LinkState
import com.example.appmascotas.bt.Telemetry
import com.example.appmascotas.cloud.CloudStatus
import com.example.appmascotas.cloud.CloudSync
import com.example.appmascotas.cloud.RemoteCommand
import com.example.appmascotas.cloud.RemoteState
import com.example.appmascotas.auth.AuthService
import com.example.appmascotas.auth.UserProfile
import com.example.appmascotas.auth.Validators
import com.google.firebase.firestore.ListenerRegistration
import com.example.appmascotas.data.EventRow
import com.example.appmascotas.data.PetDoorDb
import com.example.appmascotas.security.SecureProtocol
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Cerebro de la app (vive mientras viva el proceso, no la pantalla): une base de datos local,
 * enlace Bluetooth con el Arduino, sincronización con la nube y el estado que muestra la UI.
 * Así rotar la pantalla no corta la conexión con el Arduino.
 */
class PetDoorController(private val app: Application) : BluetoothLink.Callback, CloudSync.Host {

    // ---------- Estado observable por la interfaz (Compose) ----------
    var linkState by mutableStateOf(LinkState.DESCONECTADO); private set
    var linkDetail by mutableStateOf("Sin conexión con el Arduino"); private set
    var linkAttempt by mutableIntStateOf(0); private set
    var deviceLabel by mutableStateOf(""); private set
    var telemetry by mutableStateOf<Telemetry?>(null); private set
    var lastDataMs by mutableLongStateOf(0L); private set
    var rejectedFrames by mutableIntStateOf(0); private set

    var remoteMode by mutableStateOf(false); private set
    var remoteState by mutableStateOf<RemoteState?>(null); private set

    var cloudStatus by mutableStateOf(CloudStatus.SIN_SESION); private set
    var cloudDetail by mutableStateOf(""); private set
    var pendingCount by mutableIntStateOf(0); private set
    var lastSyncMs by mutableLongStateOf(0L); private set

    val events = mutableStateListOf<EventRow>()
    val remoteEvents = mutableStateListOf<EventRow>()

    var currentUser by mutableStateOf(""); private set
    var profile by mutableStateOf<UserProfile?>(null); private set
    var message by mutableStateOf<String?>(null)

    // ---------- Componentes ----------
    val db = PetDoorDb(app)
    val protocol = SecureProtocol()
    val authService = AuthService(app)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs = app.getSharedPreferences("petdoor", Context.MODE_PRIVATE)

    private val link = BluetoothLink(app, protocol, this)
    private val cloud = CloudSync(app, db, this)

    // ---------- Control interno ----------
    @Volatile override var foreground: Boolean = true
    private var lastDoorOpen: Boolean? = null
    private var lastAuto: Boolean? = null
    private var lastReadingLogMs = 0L
    private var lastPeriodCmdMs = 0L
    private var lastSecurityLogMs = 0L
    private var lastLoggedGroup = ""

    init {
        deviceLabel = prefs.getString(KEY_DEVICE_LABEL, "") ?: ""
        try {
            events.addAll(db.recentEvents(60))
            pendingCount = db.pendingCount()
        } catch (_: Exception) {
        }
        restoreSession()
    }

    // ================= Sesión =================

    private var profileWatch: ListenerRegistration? = null

    /** Firebase conserva la sesión en el celular: se revalida el perfil (activo, rol) antes de dejar entrar. */
    private fun restoreSession() {
        authService.restore { p, error ->
            if (p != null) onLogin(p, restored = true)
            else if (error != null) main.post { message = error }
        }
    }

    fun onLogin(p: UserProfile, restored: Boolean = false) {
        profile = p
        currentUser = Validators.clean(p.nombre.ifEmpty { p.email }, Validators.NAME_MAX)
        addEvent("LOGIN", if (restored) "Sesión restaurada (${p.rol})" else "Inicio de sesión (${p.rol})", "LOCAL")
        profileWatch?.remove()
        profileWatch = authService.listenOwnProfile(p.uid) { updated ->
            main.post {
                when {
                    updated == null || !updated.activo -> {
                        message = "Tu cuenta fue desactivada o eliminada por el administrador"
                        addEvent("SEGURIDAD", "Sesión cerrada: la cuenta ya no tiene acceso", "SISTEMA")
                        onLogout(register = false)
                    }
                    updated.rol != profile?.rol -> profile = updated
                }
            }
        }
    }

    fun onLogout(register: Boolean = true) {
        profileWatch?.remove()
        profileWatch = null
        if (register) addEvent("LOGOUT", "Cierre de sesión", "LOCAL")
        link.disconnect()
        remoteMode = false
        remoteState = null
        telemetry = null
        authService.logout()
        main.post { currentUser = ""; profile = null }      // después de registrar el evento con el usuario correcto
    }

    // ================= Bluetooth =================

    fun hasBluetoothPermission() = link.hasPermission()
    fun isBluetoothSupported() = link.isSupported()
    fun isBluetoothOn() = link.isBluetoothOn()
    fun bondedDevices(): List<BluetoothDevice> = link.bondedDevices()

    /** Conecta con el dispositivo guardado, o con el único HC-05 vinculado. false = hay que elegir uno. */
    fun connectPreferred(): Boolean {
        val devices = link.bondedDevices()
        val savedAddr = prefs.getString(KEY_DEVICE_ADDR, null)
        val candidate = devices.firstOrNull { it.address == savedAddr }
            ?: devices.singleOrNull { nameOf(it).contains(com.example.appmascotas.security.SecurityConfig.HC05_NAME_HINT, true) }
        return if (candidate != null) {
            connectTo(candidate)
            true
        } else false
    }

    fun connectTo(d: BluetoothDevice) {
        remoteMode = false
        remoteState = null
        deviceLabel = "${nameOf(d)} · ${d.address}"
        prefs.edit().putString(KEY_DEVICE_ADDR, d.address).putString(KEY_DEVICE_LABEL, deviceLabel).apply()
        link.connect(d)
    }

    fun disconnect() {
        link.disconnect()
    }

    fun nameOf(d: BluetoothDevice): String =
        try { d.name ?: d.address } catch (_: SecurityException) { d.address }

    // ================= Modo directo / remoto =================

    fun switchRemoteMode(enabled: Boolean) {
        if (enabled == remoteMode) return
        if (enabled) {
            link.disconnect()          // libera el HC-05 para que otro celular se conecte directo
            telemetry = null
            remoteMode = true
            addEvent("CONEXION", "Modo remoto (por la nube) activado", "LOCAL")
        } else {
            remoteMode = false
            remoteState = null
            remoteEvents.clear()
            addEvent("CONEXION", "Modo directo (Bluetooth) activado", "LOCAL")
        }
    }

    // ================= Órdenes =================

    /** cmd: "1" abrir, "0" cerrar, "A" automático. */
    fun sendCommand(cmd: String) {
        if (profile == null) return          // mínimo privilegio: sin sesión no hay órdenes
        val name = commandName(cmd)
        if (!remoteMode) {
            io.execute {
                val ok = link.send(cmd)
                if (ok) {
                    addEvent("CMD", "$name (orden '$cmd' enviada cifrada)", "LOCAL")
                } else {
                    addEvent("CMD_FALLIDO", "$name NO enviado: sin enlace con el Arduino", "LOCAL")
                    main.post { message = "Sin enlace con el Arduino. La puerta sigue funcionando sola en modo automático." }
                }
            }
        } else {
            val rs = remoteState
            val gatewayOk = rs != null && rs.gatewayConnected &&
                System.currentTimeMillis() - rs.updatedMs < GATEWAY_STALE_MS
            if (cloudStatus == CloudStatus.SIN_SESION || cloudStatus == CloudStatus.SIN_RED || !gatewayOk) {
                addEvent("CMD_FALLIDO", "$name NO enviado: ningún celular está conectado al Arduino o no hay Internet", "REMOTO")
                message = "No se puede: ningún celular está conectado al Arduino o no hay Internet."
                return
            }
            val id = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val sig = protocol.sign("$cmd|$ts|$id")
            cloud.submitCommand(RemoteCommand(id, cmd, ts, currentUser, sig))
            addEvent("CMD", "$name solicitado vía nube (se ejecuta cuando la pasarela lo reciba)", "REMOTO")
        }
    }

    private fun commandName(cmd: String) = when (cmd) {
        "1" -> "Abrir puerta"
        "0" -> "Cerrar puerta"
        "A" -> "Modo automático"
        else -> "Orden $cmd"
    }

    // ================= Ciclo de vida (ahorro de energía) =================

    /** En segundo plano el Arduino envía datos cada 5 s en vez de cada 1 s: menos radio y menos CPU. */
    fun onAppForeground(fg: Boolean) {
        foreground = fg
        link.desiredPeriodMs = if (fg) 1000L else 5000L
        if (link.isLinked()) io.execute { link.send("T${link.desiredPeriodMs}") }
    }

    // ================= Bitácora =================

    fun addEvent(type: String, detail: String, source: String = "LOCAL") {
        val e = EventRow(
            id = UUID.randomUUID().toString(),
            ts = System.currentTimeMillis(),
            user = currentUser.ifEmpty { "sistema" },
            source = source.take(20),
            type = type.take(20),
            detail = Validators.clean(detail, 300),
            synced = false
        )
        io.execute {
            try {
                db.insertEvent(e)
            } catch (_: Exception) {
            }
            cloud.requestFlush()
        }
        main.post {
            events.add(0, e)
            while (events.size > 80) events.removeAt(events.size - 1)
        }
    }

    // ================= BluetoothLink.Callback =================

    override fun onState(state: LinkState, detail: String, attempt: Int) {
        main.post {
            linkState = state
            linkDetail = detail
            linkAttempt = attempt
            if (state == LinkState.CONECTADO) lastDataMs = System.currentTimeMillis()
            // Se registra solo cuando cambia el "grupo" de estado, para no llenar la bitácora de reintentos.
            val group = when (state) {
                LinkState.CONECTANDO, LinkState.RECONECTANDO -> "INTENTANDO"
                else -> state.name
            }
            if (group != lastLoggedGroup) {
                lastLoggedGroup = group
                addEvent("CONEXION", "${state.name}: $detail", "SISTEMA")
            }
        }
    }

    override fun onTelemetry(t: Telemetry) {
        main.post {
            val now = System.currentTimeMillis()
            telemetry = t
            lastDataMs = now

            if (lastDoorOpen != null && (lastDoorOpen != t.doorOpen || lastAuto != t.autoMode)) {
                addEvent(
                    "ESTADO",
                    "Puerta ${if (t.doorOpen) "ABIERTA" else "CERRADA"} · modo ${if (t.autoMode) "automático" else "manual"}",
                    "ARDUINO"
                )
            }
            lastDoorOpen = t.doorOpen
            lastAuto = t.autoMode

            if (now - lastReadingLogMs >= 60_000) {
                lastReadingLogMs = now
                addEvent("LECTURA", "Distancia ${distanceText(t.distanceCm)}", "ARDUINO")
            }

            // Mantiene el periodo de telemetría deseado (también si el Arduino se reinició).
            if (t.periodMs != link.desiredPeriodMs && now - lastPeriodCmdMs > 3000) {
                lastPeriodCmdMs = now
                io.execute { link.send("T${link.desiredPeriodMs}") }
            }
        }
    }

    override fun onDeviceMessage(text: String) {
        when {
            // Los latidos ("?") y el ajuste de periodo ("T...") no se anotan: llenarían la bitácora.
            text == "ACK:?" || text.startsWith("ACK:T") -> {}
            text.startsWith("ACK:") -> addEvent("ACK", "Arduino confirmó la orden '${text.removePrefix("ACK:")}'", "ARDUINO")
            text.startsWith("ERR:") -> addEvent("ALERTA", "Arduino rechazó una orden (${text.removePrefix("ERR:")})", "ARDUINO")
        }
    }

    override fun onRejectedFrame(reason: String) {
        main.post {
            rejectedFrames++
            val now = System.currentTimeMillis()
            if (now - lastSecurityLogMs > 30_000) {
                lastSecurityLogMs = now
                addEvent("SEGURIDAD", "$reason (total rechazadas: $rejectedFrames)", "SISTEMA")
            }
        }
    }

    // ================= CloudSync.Host =================

    override val gatewayActive: Boolean
        get() = !remoteMode && linkState == LinkState.CONECTADO

    override val monitorMode: Boolean
        get() = remoteMode

    override fun gatewaySnapshot(): Map<String, Any>? {
        val t = telemetry ?: return null
        return mapOf(
            "distancia" to t.distanceCm,
            "puerta" to if (t.doorOpen) "ABIERTA" else "CERRADA",
            "modo" to if (t.autoMode) "AUTO" else "MANUAL",
            "conectado" to true,
            "pasarela" to currentUser.ifEmpty { "desconocido" }
        )
    }

    override fun onStatus(status: CloudStatus, pending: Int, lastSyncMs: Long, detail: String) {
        main.post {
            cloudStatus = status
            pendingCount = pending
            this.lastSyncMs = lastSyncMs
            cloudDetail = detail
        }
    }

    override fun onRemoteState(state: RemoteState?) {
        main.post { remoteState = state }
    }

    override fun onRemoteEvents(list: List<EventRow>) {
        main.post {
            remoteEvents.clear()
            remoteEvents.addAll(list)
        }
    }

    override fun handleRemoteCommand(c: RemoteCommand): String {
        val fresh = abs(System.currentTimeMillis() - c.ts) <= 120_000
        val valid = c.cmd in ALLOWED_REMOTE && protocol.verifySign("${c.cmd}|${c.ts}|${c.id}", c.sig)
        if (!valid || !fresh) {
            addEvent("SEGURIDAD", "Orden remota RECHAZADA de '${c.user}' (firma inválida, vencida o no permitida)", "REMOTO")
            return "rechazado"
        }
        val ok = link.send(c.cmd)
        addEvent(
            if (ok) "CMD" else "CMD_FALLIDO",
            "Orden remota de '${c.user}': ${commandName(c.cmd)}" + if (ok) "" else " (sin enlace con el Arduino)",
            "REMOTO"
        )
        return if (ok) "ejecutado" else "rechazado"
    }

    override fun onRemoteCommandSent(id: String, ok: Boolean) {
        if (!ok) {
            addEvent("CMD_FALLIDO", "No se pudo publicar la orden remota (sin Internet o nube no disponible)", "REMOTO")
            main.post { message = "No se pudo enviar la orden: revisa tu conexión a Internet." }
        }
    }

    companion object {
        private const val KEY_DEVICE_ADDR = "device_addr"
        private const val KEY_DEVICE_LABEL = "device_label"
        private const val GATEWAY_STALE_MS = 45_000L
        private val ALLOWED_REMOTE = setOf("1", "0", "A")

        fun distanceText(cm: Int) = if (cm >= 400) "sin lectura" else "$cm cm"
    }
}
