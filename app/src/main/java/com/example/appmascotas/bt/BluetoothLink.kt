package com.example.appmascotas.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.appmascotas.security.SecureProtocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Enlace Bluetooth clásico (perfil SPP / RFCOMM) con el módulo HC-05 del Arduino.
 *
 *  - Todo mensaje viaja cifrado y firmado (ver [SecureProtocol]).
 *  - El enlace solo se declara CONECTADO cuando llega la primera trama AUTENTICADA del Arduino
 *    (no basta con abrir el socket): así se detecta un módulo equivocado o una clave distinta.
 *  - Reconexión automática con espera creciente (2 s, 4 s, 8 s ... máx. 30 s) para ahorrar batería.
 *  - Vigilante (watchdog): si deja de llegar telemetría, se considera enlace caído y se reconecta.
 */
@SuppressLint("MissingPermission")
class BluetoothLink(
    context: Context,
    private val protocol: SecureProtocol,
    private val callback: Callback
) {
    interface Callback {
        fun onState(state: LinkState, detail: String, attempt: Int)
        fun onTelemetry(t: Telemetry)
        fun onDeviceMessage(text: String)
        fun onRejectedFrame(reason: String)
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val exec = Executors.newScheduledThreadPool(2)
    private val lock = Any()

    @Volatile private var wanted = false
    @Volatile private var device: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var generation = 0
    private var attempt = 0
    private var connecting = false
    @Volatile private var verified = false
    @Volatile private var lastRxMs = 0L
    @Volatile private var openedAtMs = 0L
    @Volatile private var lastTxMs = 0L
    private var sendSeq = 0L
    private var lastInSeq = 0L
    private var pending: ScheduledFuture<*>? = null

    /** Periodo de telemetría que la app espera del Arduino (más lento en segundo plano). */
    @Volatile var desiredPeriodMs = 1000L

    init {
        exec.scheduleWithFixedDelay({ watchdog() }, 2, 2, TimeUnit.SECONDS)
    }

    // ---------------- API pública ----------------

    fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
        else Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun isSupported() = adapter != null
    fun isBluetoothOn() = adapter?.isEnabled == true

    /** Dispositivos ya vinculados desde Ajustes de Android (no se escanea: menos permisos y batería). */
    fun bondedDevices(): List<BluetoothDevice> =
        if (!hasPermission()) emptyList() else adapter?.bondedDevices?.toList().orEmpty()

    fun isLinked(): Boolean = verified

    fun connect(d: BluetoothDevice) {
        synchronized(lock) {
            wanted = true
            device = d
            attempt = 0
            generation++
            cancelPendingLocked()
            closeSocketLocked()
            verified = false
        }
        callback.onState(LinkState.CONECTANDO, "Conectando con ${label(d)}…", 0)
        schedule(0)
    }

    fun disconnect() {
        synchronized(lock) {
            wanted = false
            generation++
            cancelPendingLocked()
            closeSocketLocked()
            verified = false
        }
        callback.onState(LinkState.DESCONECTADO, "Desconectado", 0)
    }

    /** Envía una orden cifrada y firmada. Devuelve false si no hay enlace verificado. */
    fun send(command: String): Boolean {
        var failure: String? = null
        val ok = synchronized(lock) {
            val out = output
            if (out == null || !verified) {
                false
            } else {
                try {
                    sendSeq++
                    val frame = protocol.encode(SecureProtocol.DIR_APP, sendSeq, command)
                    out.write((frame + "\n").toByteArray(Charsets.US_ASCII))
                    out.flush()
                    lastTxMs = System.currentTimeMillis()
                    true
                } catch (e: IOException) {
                    failure = e.message ?: "error de escritura"
                    false
                }
            }
        }
        failure?.let { msg -> exec.execute { handleFailure("Fallo al enviar: $msg") } }
        return ok
    }

    fun shutdown() {
        disconnect()
        exec.shutdownNow()
    }

    // ---------------- Conexión ----------------

    private fun schedule(delayMs: Long) {
        synchronized(lock) {
            cancelPendingLocked()
            pending = exec.schedule({ attemptConnect() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun attemptConnect() {
        val d: BluetoothDevice
        val myAttempt: Int
        synchronized(lock) {
            if (!wanted || connecting) return
            d = device ?: return
            connecting = true
            attempt++
            myAttempt = attempt
        }
        try {
            if (adapter == null) {
                fatal("Este equipo no tiene Bluetooth")
                return
            }
            if (!hasPermission()) {
                fatal("Falta el permiso de Bluetooth")
                return
            }
            if (!adapter.isEnabled) {
                handleFailure("Bluetooth apagado")
                return
            }
            callback.onState(
                if (myAttempt > 1) LinkState.RECONECTANDO else LinkState.CONECTANDO,
                "Conectando con ${label(d)}… (intento $myAttempt)", myAttempt
            )
            // Si hubiera una búsqueda activa, se cancela: descubrir mientras se conecta gasta batería y la vuelve lenta.
            // (En Android 12+ cancelDiscovery exige BLUETOOTH_SCAN; esta app no lo pide, y tampoco escanea.)
            if (Build.VERSION.SDK_INT < 31) adapter.cancelDiscovery()
            val s = d.createRfcommSocketToServiceRecord(SPP_UUID)
            val myGen: Int
            synchronized(lock) {
                if (!wanted) {
                    s.close()
                    return
                }
                socket = s
                myGen = generation
            }
            s.connect()
            synchronized(lock) {
                if (!wanted || myGen != generation) {
                    try { s.close() } catch (_: IOException) {}
                    return
                }
                output = s.outputStream
                verified = false
                lastInSeq = 0
                openedAtMs = System.currentTimeMillis()
                lastRxMs = openedAtMs
            }
            callback.onState(LinkState.CONECTANDO, "Enlace abierto. Verificando que sea el PetDoor…", myAttempt)
            startReader(s.inputStream, myGen)
        } catch (e: SecurityException) {
            fatal("Permiso de Bluetooth denegado")
        } catch (e: IOException) {
            handleFailure(
                if (myAttempt >= 3) "No responde (¿otro celular ya está conectado al HC-05?)"
                else "No se pudo conectar"
            )
        } finally {
            synchronized(lock) { connecting = false }
        }
    }

    private fun startReader(input: InputStream, gen: Int) {
        Thread({
            val line = StringBuilder()
            val buf = ByteArray(256)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("Conexión cerrada")
                    for (i in 0 until n) {
                        val c = (buf[i].toInt() and 0xFF).toChar()
                        when {
                            c == '\n' -> {
                                if (line.isNotEmpty()) handleLine(line.toString(), gen)
                                line.setLength(0)
                            }
                            c == '\r' -> {}
                            line.length < MAX_LINE -> line.append(c)
                            else -> line.setLength(0)     // línea demasiado larga: basura, se descarta
                        }
                    }
                }
            } catch (e: IOException) {
                val current = synchronized(lock) { gen == generation && wanted }
                if (current) handleFailure("Enlace perdido")
            }
        }, "bt-reader").start()
    }

    private fun handleLine(raw: String, gen: Int) {
        val frame = protocol.decode(raw, SecureProtocol.DIR_DEVICE)
        if (frame == null) {
            callback.onRejectedFrame("Trama con firma inválida o formato incorrecto")
            return
        }
        var firstValid = false
        synchronized(lock) {
            if (gen != generation) return
            if (frame.seq <= lastInSeq) {
                callback.onRejectedFrame("Trama repetida (posible replay)")
                return
            }
            lastInSeq = frame.seq
            lastRxMs = System.currentTimeMillis()
            if (!verified) {
                verified = true
                attempt = 0
                firstValid = true
                lastTxMs = lastRxMs
            }
        }
        if (firstValid) {
            callback.onState(LinkState.CONECTADO, "Enlace seguro establecido", 0)
        }
        val t = Telemetry.parse(frame.payload)
        if (t != null) {
            synchronized(lock) { if (t.lastSeq > sendSeq) sendSeq = t.lastSeq }
            callback.onTelemetry(t)
        } else {
            callback.onDeviceMessage(frame.payload)
        }
    }

    // ---------------- Reconexión y vigilancia ----------------

    private fun watchdog() {
        var keepAlive = false
        val reason: String? = synchronized(lock) {
            if (!wanted || connecting || socket == null) return
            val now = System.currentTimeMillis()
            if (verified) {
                val timeout = maxOf(6000L, desiredPeriodMs * 3 + 2000L)
                if (now - lastRxMs > timeout) {
                    "Sin datos del Arduino"
                } else {
                    // Latido: el Arduino vuelve solo a modo automático si pasa mucho tiempo sin órdenes válidas
                    // (plan de contingencia). Un "?" cada 20 s le confirma que el enlace sigue vivo.
                    if (now - lastTxMs > KEEPALIVE_MS) keepAlive = true
                    null
                }
            } else {
                if (now - openedAtMs > VERIFY_TIMEOUT_MS)
                    "El módulo no respondió con una trama válida (¿clave distinta?)"
                else null
            }
        }
        if (reason != null) handleFailure(reason) else if (keepAlive) send("?")
    }

    /** Cierra el enlace actual y programa un nuevo intento con espera creciente. */
    private fun handleFailure(reason: String) {
        val delay: Long
        val n: Int
        synchronized(lock) {
            if (!wanted) return
            generation++
            closeSocketLocked()
            verified = false
            delay = backoffMs(attempt)
            n = attempt
        }
        callback.onState(
            LinkState.RECONECTANDO,
            "$reason. Reintentando en ${delay / 1000} s…", n + 1
        )
        schedule(delay)
    }

    private fun fatal(message: String) {
        synchronized(lock) {
            wanted = false
            generation++
            closeSocketLocked()
            verified = false
        }
        callback.onState(LinkState.ERROR, message, 0)
    }

    private fun backoffMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 4)
        return minOf(2000L shl shift, 30_000L)      // 2, 4, 8, 16, 30, 30...
    }

    private fun closeSocketLocked() {
        try { output?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        output = null
        socket = null
    }

    private fun cancelPendingLocked() {
        pending?.cancel(false)
        pending = null
    }

    private fun label(d: BluetoothDevice): String =
        try { d.name ?: d.address } catch (_: SecurityException) { d.address }

    companion object {
        /** UUID estándar del perfil de puerto serie (SPP) que usa el HC-05. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAX_LINE = 200
        private const val VERIFY_TIMEOUT_MS = 8000L
        private const val KEEPALIVE_MS = 20_000L
    }
}
