package com.example.appmascotas.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.appmascotas.data.EventRow
import com.example.appmascotas.data.PetDoorDb
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class CloudStatus { SIN_SESION, SIN_RED, SINCRONIZADA, PENDIENTES, ERROR }

/** Último estado de la puerta publicado en la nube por el celular que está conectado al Arduino. */
data class RemoteState(
    val distanceCm: Int,
    val doorOpen: Boolean,
    val autoMode: Boolean,
    val gatewayConnected: Boolean,
    val updatedMs: Long,
    val gateway: String
)

/** Orden pedida desde otro celular (modo remoto). Viene firmada con HMAC. */
data class RemoteCommand(val id: String, val cmd: String, val ts: Long, val user: String, val sig: String)

/**
 * Sincronización con Firebase Firestore y puente entre celulares.
 *
 * El módulo HC-05 solo admite UN enlace Bluetooth a la vez, así que cuando varios celulares
 * quieren "ver qué pasa" se usa la nube:
 *   - El celular conectado al Arduino (pasarela) publica el estado y sube los eventos.
 *   - Los demás (modo remoto) escuchan el estado/eventos en tiempo real y envían órdenes firmadas,
 *     que la pasarela verifica y reenvía al Arduino por Bluetooth.
 *
 * Almacenamiento temporal: todo evento se guarda primero en SQLite (synced = 0) y se sube cuando
 * hay Internet; si se cae la red, se reintenta solo y no se pierde ni duplica nada (mismo id).
 * Colecciones: eventos/{id}, petdoor/estado, comandos/{id}  (permisos: firestore.rules).
 */
class CloudSync(
    context: Context,
    private val db: PetDoorDb,
    private val host: Host
) {
    interface Host {
        val gatewayActive: Boolean
        val monitorMode: Boolean
        val foreground: Boolean
        fun gatewaySnapshot(): Map<String, Any>?
        fun onStatus(status: CloudStatus, pending: Int, lastSyncMs: Long, detail: String)
        fun onRemoteState(state: RemoteState?)
        fun onRemoteEvents(list: List<EventRow>)
        /** Devuelve "ejecutado" o "rechazado". */
        fun handleRemoteCommand(c: RemoteCommand): String
        fun onRemoteCommandSent(id: String, ok: Boolean)
    }

    private val fs: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var online = false
    @Volatile private var flushRequested = true
    private var lastFlushMs = 0L
    private var lastSyncMs = 0L
    private var lastStatePublishMs = 0L
    private var lastPublishedKey = ""
    private var wasGateway = false
    private val handledCommands = LinkedHashSet<String>()

    private var stateListener: ListenerRegistration? = null
    private var eventsListener: ListenerRegistration? = null
    private var commandsListener: ListenerRegistration? = null
    private var listenersUid: String? = null

    init {
        online = checkOnline()
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    online = true
                    flushRequested = true        // al volver Internet, subir lo pendiente enseguida
                }

                override fun onLost(network: Network) {
                    online = checkOnline()
                }
            })
        } catch (_: Exception) {
        }
        exec.scheduleWithFixedDelay({ safeTick() }, 1, 2, TimeUnit.SECONDS)
    }

    fun requestFlush() {
        flushRequested = true
    }

    /** Publica una orden de un celular remoto para que la pasarela la ejecute. */
    fun submitCommand(c: RemoteCommand) {
        fs.collection("comandos").document(c.id)
            .set(mapOf("cmd" to c.cmd, "ts" to c.ts, "usuario" to c.user, "estado" to "pendiente", "firma" to c.sig))
            .addOnCompleteListener { host.onRemoteCommandSent(c.id, it.isSuccessful) }
    }

    fun shutdown() {
        stopListeners()
        exec.shutdownNow()
    }

    // ---------------- Ciclo principal ----------------

    private fun safeTick() {
        try {
            tick()
        } catch (e: Exception) {
            host.onStatus(CloudStatus.ERROR, safePending(), lastSyncMs, e.message ?: "Error de sincronización")
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val pending = safePending()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            stopListeners()
            host.onStatus(CloudStatus.SIN_SESION, pending, lastSyncMs, "Sin sesión: los datos se guardan en el celular")
            return
        }
        online = online || checkOnline()
        if (!online) {
            host.onStatus(CloudStatus.SIN_RED, pending, lastSyncMs, "Sin Internet: los datos se guardan en el celular")
            return
        }
        try {
            // 1) Subir eventos pendientes (cola offline)
            if (flushRequested || (pending > 0 && now - lastFlushMs >= 10_000)) {
                flushRequested = false
                lastFlushMs = now
                flush()
            }

            // 2) Pasarela: publicar estado
            val gateway = host.gatewayActive
            if (gateway) {
                publishState(now)
            } else if (wasGateway) {
                runCatching {
                    Tasks.await(fs.collection("petdoor").document("estado").update(mapOf("conectado" to false, "ts" to now)), 8, TimeUnit.SECONDS)
                }
            }
            wasGateway = gateway

            // 3) Escuchas en tiempo real según el rol del celular en este momento
            syncListeners(uid, gateway)

            lastSyncMs = now
            val left = safePending()
            host.onStatus(
                if (left == 0) CloudStatus.SINCRONIZADA else CloudStatus.PENDIENTES, left, lastSyncMs,
                if (left == 0) "Todo sincronizado con la nube" else "$left eventos por sincronizar"
            )
        } catch (e: Exception) {
            host.onStatus(CloudStatus.ERROR, safePending(), lastSyncMs, "Nube no disponible: ${e.message ?: "error de red"}")
        }
    }

    private fun flush() {
        val rows = db.unsynced(25)
        val done = ArrayList<String>()
        try {
            for (r in rows) {
                val data = mapOf(
                    "ts" to r.ts, "usuario" to r.user, "origen" to r.source,
                    "tipo" to r.type, "detalle" to r.detail
                )
                Tasks.await(fs.collection("eventos").document(r.id).set(data), 10, TimeUnit.SECONDS)
                done.add(r.id)
            }
        } finally {
            db.markSynced(done)          // lo ya subido queda marcado aunque falle uno posterior
        }
        if (rows.size == 25) flushRequested = true
        db.pruneSynced()
    }

    private fun publishState(now: Long) {
        val snap = host.gatewaySnapshot() ?: return
        val key = "${snap["puerta"]}|${snap["modo"]}|${snap["conectado"]}"
        if (key != lastPublishedKey || now - lastStatePublishMs >= 15_000) {
            val fields = HashMap<String, Any>(snap)
            fields["ts"] = now
            Tasks.await(fs.collection("petdoor").document("estado").set(fields), 10, TimeUnit.SECONDS)
            lastPublishedKey = key
            lastStatePublishMs = now
        }
    }

    // ---------------- Escuchas en tiempo real ----------------

    private fun syncListeners(uid: String, gateway: Boolean) {
        if (listenersUid != uid) stopListeners()
        listenersUid = uid

        val wantMonitor = host.monitorMode && host.foreground
        if (wantMonitor && stateListener == null) {
            stateListener = fs.collection("petdoor").document("estado")
                .addSnapshotListener { snap, e -> if (e == null) host.onRemoteState(parseState(snap)) }
            eventsListener = fs.collection("eventos").orderBy("ts", Query.Direction.DESCENDING).limit(40)
                .addSnapshotListener { snap, e ->
                    if (e == null && snap != null) host.onRemoteEvents(snap.documents.map { parseEvent(it) })
                }
        } else if (!wantMonitor && stateListener != null) {
            stateListener?.remove(); stateListener = null
            eventsListener?.remove(); eventsListener = null
        }

        if (gateway && commandsListener == null) {
            commandsListener = fs.collection("comandos").whereEqualTo("estado", "pendiente")
                .addSnapshotListener { snap, e ->
                    if (e != null || snap == null) return@addSnapshotListener
                    for (d in snap.documents) {
                        if (!handledCommands.add(d.id)) continue      // ya procesada: no se ejecuta dos veces
                        if (handledCommands.size > 200) handledCommands.remove(handledCommands.first())
                        val c = RemoteCommand(
                            id = d.id,
                            cmd = d.getString("cmd").orEmpty(),
                            ts = d.getLong("ts") ?: 0L,
                            user = d.getString("usuario").orEmpty(),
                            sig = d.getString("firma").orEmpty()
                        )
                        exec.execute {
                            val result = host.handleRemoteCommand(c)
                            d.reference.update("estado", result)
                        }
                    }
                }
        } else if (!gateway && commandsListener != null) {
            commandsListener?.remove(); commandsListener = null
        }
    }

    private fun stopListeners() {
        stateListener?.remove(); stateListener = null
        eventsListener?.remove(); eventsListener = null
        commandsListener?.remove(); commandsListener = null
    }

    private fun parseState(d: DocumentSnapshot?): RemoteState? {
        if (d == null || !d.exists()) return null
        return RemoteState(
            distanceCm = (d.getLong("distancia") ?: 999L).coerceIn(0L, 999L).toInt(),
            doorOpen = d.getString("puerta") == "ABIERTA",
            autoMode = d.getString("modo") == "AUTO",
            gatewayConnected = d.getBoolean("conectado") ?: false,
            updatedMs = d.getLong("ts") ?: 0L,
            gateway = d.getString("pasarela").orEmpty().take(60)
        )
    }

    private fun parseEvent(d: DocumentSnapshot) = EventRow(
        id = d.id,
        ts = d.getLong("ts") ?: 0L,
        user = d.getString("usuario").orEmpty(),
        source = d.getString("origen").orEmpty(),
        type = d.getString("tipo").orEmpty(),
        detail = d.getString("detalle").orEmpty(),
        synced = true
    )

    private fun safePending(): Int = try { db.pendingCount() } catch (_: Exception) { 0 }

    private fun checkOnline(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
