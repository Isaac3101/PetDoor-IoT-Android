// secret.h - Claves compartidas con la app Android (SecurityConfig.kt)
// DEBEN ser iguales en ambos lados. Cambialas por claves propias antes de la demo final:
//   python3 -c "import secrets; print(secrets.token_hex(16))"   (una para ENC y otra para MAC)
// NO subas tus claves reales a un repositorio publico.
#ifndef SECRET_H
#define SECRET_H

#include <stdint.h>

// AES-128: cifra el contenido de cada mensaje (confidencialidad)
const uint8_t ENC_KEY[16] = { 0x7B, 0xC1, 0xD0, 0xA0, 0xD7, 0xD6, 0x29, 0x0A, 0x58, 0x35, 0xCB, 0x03, 0x73, 0x08, 0x0B, 0xBA };

// HMAC-SHA256: firma cada mensaje (integridad / autenticidad)
const uint8_t MAC_KEY[16] = { 0xD4, 0x6D, 0xB9, 0x7E, 0xFB, 0x96, 0x85, 0x33, 0x32, 0x16, 0xF8, 0xB9, 0x02, 0xB4, 0x84, 0x30 };

#endif
