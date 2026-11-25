package com.example.appmascotas

import android.app.Application

/** Crea el controlador una sola vez por proceso: la conexión Bluetooth sobrevive a rotaciones de pantalla. */
class PetDoorApp : Application() {
    lateinit var controller: PetDoorController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = PetDoorController(this)
    }
}
