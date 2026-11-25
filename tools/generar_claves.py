#!/usr/bin/env python3
"""Genera claves nuevas (AES y HMAC) y las escribe en la app y en el sketch a la vez.

Uso (desde la raiz del proyecto):   python3 tools/generar_claves.py
Despues: vuelve a compilar la app y vuelve a cargar el sketch en el Arduino.
"""
import re
import secrets
from pathlib import Path

root = Path(__file__).resolve().parent.parent
kt = root / "app/src/main/java/com/example/appmascotas/security/SecurityConfig.kt"
h = root / "arduino/PetDoor/secret.h"

enc, mac = secrets.token_hex(16), secrets.token_hex(16)
as_c = lambda x: ", ".join(f"0x{x[i:i+2].upper()}" for i in range(0, 32, 2))

t = kt.read_text(encoding="utf-8")
t = re.sub(r'(ENC_KEY_HEX = ")[0-9a-fA-F]{32}(")', rf"\g<1>{enc}\2", t)
t = re.sub(r'(MAC_KEY_HEX = ")[0-9a-fA-F]{32}(")', rf"\g<1>{mac}\2", t)
kt.write_text(t, encoding="utf-8")

c = h.read_text(encoding="utf-8")
c = re.sub(r"(ENC_KEY\[16\] = \{)[^}]*(\};)", rf"\g<1> {as_c(enc)} \2", c)
c = re.sub(r"(MAC_KEY\[16\] = \{)[^}]*(\};)", rf"\g<1> {as_c(mac)} \2", c)
h.write_text(c, encoding="utf-8")

print("Claves nuevas escritas en SecurityConfig.kt y secret.h")
print("Recompila la app y vuelve a cargar el sketch. No subas estos archivos a un repositorio publico.")
