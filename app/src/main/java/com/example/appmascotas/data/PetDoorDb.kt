package com.example.appmascotas.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Un registro de la bitácora (auditoría). Es también la cola de sincronización offline. */
data class EventRow(
    val id: String,
    val ts: Long,
    val user: String,
    val source: String,   // LOCAL, ARDUINO, REMOTO, SISTEMA
    val type: String,     // LOGIN, CMD, ESTADO, LECTURA, CONEXION, SEGURIDAD...
    val detail: String,
    val synced: Boolean
)

/**
 * Base de datos local SQLite:
 *  - events : bitácora de eventos y lecturas. synced = 0 significa "pendiente de subir a la nube".
 *             Así la app funciona sin Internet y sincroniza sola cuando vuelve la conexión.
 */
class PetDoorDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "petdoor.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events (" +
                "id TEXT PRIMARY KEY, " +
                "ts INTEGER NOT NULL, " +
                "usr TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "type TEXT NOT NULL, " +
                "detail TEXT NOT NULL, " +
                "synced INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts DESC)")
        db.execSQL("CREATE INDEX idx_events_synced ON events(synced)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2: las cuentas ahora viven en Firebase Authentication; se elimina la tabla local de usuarios.
        db.execSQL("DROP TABLE IF EXISTS users")
    }

    // ---------- Eventos ----------

    fun insertEvent(e: EventRow): Boolean {
        val cv = ContentValues().apply {
            put("id", e.id)
            put("ts", e.ts)
            put("usr", e.user)
            put("source", e.source)
            put("type", e.type)
            put("detail", e.detail)
            put("synced", if (e.synced) 1 else 0)
        }
        return writableDatabase.insertWithOnConflict(
            "events", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun recentEvents(limit: Int): List<EventRow> =
        query("SELECT id, ts, usr, source, type, detail, synced FROM events ORDER BY ts DESC LIMIT $limit")

    /** Eventos aún no subidos a la nube, del más antiguo al más nuevo. */
    fun unsynced(limit: Int): List<EventRow> =
        query("SELECT id, ts, usr, source, type, detail, synced FROM events WHERE synced = 0 ORDER BY ts ASC LIMIT $limit")

    fun markSynced(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL("UPDATE events SET synced = 1 WHERE id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE synced = 0", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Evita que la bitácora crezca sin límite: borra los ya sincronizados más antiguos. */
    fun pruneSynced(keep: Int = 2000) {
        writableDatabase.execSQL(
            "DELETE FROM events WHERE synced = 1 AND id NOT IN " +
                "(SELECT id FROM events ORDER BY ts DESC LIMIT $keep)"
        )
    }

    private fun query(sql: String): List<EventRow> {
        val out = ArrayList<EventRow>()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    EventRow(
                        id = c.getString(0),
                        ts = c.getLong(1),
                        user = c.getString(2),
                        source = c.getString(3),
                        type = c.getString(4),
                        detail = c.getString(5),
                        synced = c.getInt(6) == 1
                    )
                )
            }
        }
        return out
    }
}
