# PetDoor IoT (Android + Arduino)

App Android (Kotlin + Jetpack Compose) que controla y monitorea una puerta para mascotas con Arduino Uno + HC-05.

## Qué hace
- Login con **Firebase Authentication** (correo/contraseña) y **roles** (admin / usuario) guardados en Firestore (`users/{uid}`).
- El **admin** gestiona usuarios desde la app: **agregar, editar (nombre, rol, activo) y eliminar**. No hay registro libre.
- Recuadro de **estado de conexión** (verde/ámbar/rojo) con el Arduino, reconexión automática y vigilante.
- Comunicación cifrada y firmada (AES-128-CTR + HMAC-SHA256 + número de secuencia anti-repetición).
- Bitácora en SQLite que se sincroniza a Firestore cuando hay Internet (cola offline).
- Modo **Directo** (Bluetooth) y modo **Remoto** (otros celulares ven el estado y mandan órdenes firmadas por la nube).

## Puesta en marcha
1. **Claves**: en `security/SecurityConfig.kt` y `arduino/PetDoor/secret.h` deben ser iguales. Genera las tuyas:
   `python3 -c "import secrets; print(secrets.token_hex(16))"` (una para ENC y otra para MAC).
2. **Arduino**: IDE de Arduino → instalar biblioteca **Crypto** (Rhys Weatherley) → abrir `arduino/PetDoor/PetDoor.ino`.
   Pines: HC-05 TX→D2, RX←D3 (divisor 1k/2k), HC-SR04 TRIG D8/ECHO D7, servo D9, buzzer D5, LED D13. Ajusta si tu cableado difiere.
3. **Vincular el HC-05** en Ajustes → Bluetooth del celular (PIN 1234 o 0000). La app lo lista en "Elegir dispositivo".
4. **Firebase (obligatorio para entrar)**: proyecto `petdoor-iot` con Authentication (correo/contraseña) y Firestore. Coloca tu `google-services.json` en `app/`.
   - Pega `firestore.rules` en Firestore → Reglas → Publicar.
   - Primer admin: crea el usuario en Authentication, copia su UID y crea en Firestore `users/<UID>` con `nombre`, `email`, `rol: "admin"`, `activo: true`.
   - Límites: desde el celular no se puede borrar la cuenta de Auth de otra persona (se elimina su perfil y queda sin acceso) ni fijar su contraseña (se envía correo de restablecimiento).
   Para producción hay que cerrar las reglas y agregar Firebase Auth.
5. Android Studio → Run. Pruebas unitarias: `./gradlew testDebugUnitTest`.

## Protocolo
`E,<dir>,<seq>,<cifrado_hex>,<mac_hex>\n` (dir `A` app→Arduino, `D` Arduino→app).
Órdenes: `1` abrir, `0` cerrar, `A` automático, `T<ms>` periodo de telemetría, `?` latido.
Telemetría: `D:<cm>,P:<0|1>,M:<A|M>,T:<ms>,Q:<seq>`.

## Límites conocidos
- El HC-05 admite un solo enlace Bluetooth a la vez; por eso los demás celulares usan el modo remoto (nube).
- Las claves van incrustadas en la app (suficiente para el curso; en producción se provisionan por dispositivo).
- Si el Arduino se reinicia, su contador anti-repetición vuelve a 0.

## Seguridad y validación
- **Entradas validadas**: correo, nombre (máx. 60, sin caracteres de control), contraseña nueva (8–64, letras y números), rol (solo admin/usuario). Todo se revisa en la app y, además, en las reglas de Firestore.
- **Freno de login**: 5 fallos seguidos bloquean 30 s (se duplica hasta 5 min), además del límite de Firebase.
- **Sesión**: se revalida al abrir la app y se vigila en tiempo real; si el admin desactiva o elimina una cuenta, esa sesión se cierra sola. Siempre queda al menos un admin activo y nadie puede borrarse o quitarse el rol a sí mismo.
- **Datos del Arduino**: aunque la trama venga firmada, se descartan valores fuera de rango (distancia, puerta, modo, periodo).
- **Reglas de Firestore** (`firestore.rules`): esquema y largos de cada documento, bitácora que no se edita ni se borra, órdenes remotas que solo pasan de `pendiente` a `ejecutado/rechazado`.
- **Sketch**: el número de secuencia y el periodo `T` solo aceptan dígitos.
- **Claves propias**: `python3 tools/generar_claves.py` crea claves nuevas y las escribe en la app y en `secret.h`. Recompila la app y vuelve a cargar el sketch.
- **Límites conocidos**: las claves viajan dentro del APK (cualquiera con el APK puede firmar órdenes); al reiniciarse, el Arduino pierde su contador anti-replay; no se probó con hardware real.
