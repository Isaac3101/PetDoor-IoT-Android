/*
 * PetDoor IoT - Sketch Arduino Uno R3
 * Puerta inteligente para mascotas: HC-SR04 + servo SG90 + LED + buzzer + HC-05 (Bluetooth SPP)
 *
 * Comunicacion segura con la app Android (ISO/IEC 27400):
 *   App -> Arduino :  E,A,<seq>,<cifrado_hex>,<mac_hex>\n
 *   Arduino -> App :  E,D,<seq>,<cifrado_hex>,<mac_hex>\n
 *   - Cifrado: AES-128 modo CTR (confidencialidad)
 *   - Firma:   HMAC-SHA256 truncado a 8 bytes (integridad y autenticidad)
 *   - Anti-replay: solo se aceptan ordenes con seq mayor a la ultima valida
 *
 * Ordenes (texto plano dentro del mensaje cifrado):
 *   "1" abrir (manual) | "0" cerrar (manual) | "A" modo automatico
 *   "T<ms>" periodo de telemetria (500..60000) | "?" pedir telemetria ahora
 * Telemetria (cifrada): "D:<cm>,P:<0|1>,M:<A|M>,T:<ms>,Q:<ultimo seq valido>"
 *
 * Libreria necesaria (Administrador de bibliotecas): "Crypto" de Rhys Weatherley
 *
 * Conexiones (ajusta los pines si tu cableado es distinto):
 *   HC-05 TX -> D2 | HC-05 RX <- D3 (con divisor de tension 1k/2k) | VCC 5V | GND
 *   HC-SR04 TRIG -> D8, ECHO -> D7
 *   Servo SG90 senal -> D9
 *   LED -> D13 (con resistencia) | Buzzer -> D5
 *   El HC-05 debe estar a 9600 baudios (modo datos, por defecto).
 */

#include <SoftwareSerial.h>
#include <Servo.h>
#include <AES.h>
#include <SHA256.h>
#include "secret.h"

// ---------------- Pines ----------------
const uint8_t PIN_BT_RX  = 2;   // Arduino RX  <- HC-05 TX
const uint8_t PIN_BT_TX  = 3;   // Arduino TX  -> HC-05 RX
const uint8_t PIN_ECHO   = 7;
const uint8_t PIN_TRIG   = 8;
const uint8_t PIN_SERVO  = 9;
const uint8_t PIN_LED    = 13;
const uint8_t PIN_BUZZER = 5;

// ---------------- Parametros ----------------
const uint8_t  MAX_PAYLOAD      = 48;      // bytes por mensaje (igual que la app)
const uint16_t DETECT_CM        = 15;      // distancia que abre la puerta en modo automatico
const unsigned long HOLD_OPEN_MS = 3000;   // tiempo abierta tras dejar de detectar
const unsigned long FAILSAFE_MS  = 60000;  // sin enlace valido en modo manual -> vuelve a AUTO
const uint8_t  ANGLE_CLOSED     = 0;
const uint8_t  ANGLE_OPEN       = 90;

// ---------------- Estado ----------------
SoftwareSerial bt(PIN_BT_RX, PIN_BT_TX);
Servo servo;

bool doorOpen = false;
bool autoMode = true;
uint16_t distanceCm = 999;
unsigned long telemetryPeriod = 1000;
unsigned long lastTelemetry = 0;
unsigned long lastSensor = 0;
unsigned long lastDetect = 0;
unsigned long lastValidRx = 0;
uint32_t lastSeqIn = 0;    // ultimo seq valido recibido de la app (anti-replay)
uint32_t seqOut = 0;       // contador de mensajes enviados a la app

char rxBuf[150];
uint8_t rxLen = 0;

// ---------------- Criptografia ----------------
// Keystream = AES128(ENC_KEY, IV) con IV = [dir][seq BE 4B][7 bytes 0][contador BE 4B]
void crypt(char dir, uint32_t seq, const uint8_t *in, uint8_t *out, uint8_t n) {
  AES128 aes;
  aes.setKey(ENC_KEY, 16);
  uint8_t iv[16];
  uint8_t ks[16];
  uint8_t blk = 0;
  for (uint8_t off = 0; off < n; off += 16, blk++) {
    memset(iv, 0, sizeof(iv));
    iv[0] = (uint8_t)dir;
    iv[1] = (uint8_t)(seq >> 24);
    iv[2] = (uint8_t)(seq >> 16);
    iv[3] = (uint8_t)(seq >> 8);
    iv[4] = (uint8_t)seq;
    iv[15] = blk;
    aes.encryptBlock(ks, iv);
    for (uint8_t j = 0; j < 16 && (uint8_t)(off + j) < n; j++) {
      out[off + j] = in[off + j] ^ ks[j];
    }
  }
}

// HMAC-SHA256(MAC_KEY, dir || seq BE || cifrado), truncado a 8 bytes
void computeMac(char dir, uint32_t seq, const uint8_t *cipher, uint8_t n, uint8_t *out8) {
  SHA256 sha;
  uint8_t hdr[5] = { (uint8_t)dir, (uint8_t)(seq >> 24), (uint8_t)(seq >> 16), (uint8_t)(seq >> 8), (uint8_t)seq };
  uint8_t full[32];
  sha.resetHMAC(MAC_KEY, 16);
  sha.update(hdr, 5);
  sha.update(cipher, n);
  sha.finalizeHMAC(MAC_KEY, 16, full, 32);
  memcpy(out8, full, 8);
}

bool constTimeEqual(const uint8_t *a, const uint8_t *b, uint8_t n) {
  uint8_t diff = 0;
  for (uint8_t i = 0; i < n; i++) diff |= a[i] ^ b[i];
  return diff == 0;
}

bool fromHex(const char *s, uint8_t *out, uint8_t nBytes) {
  for (uint8_t i = 0; i < nBytes; i++) {
    int hi = hexVal(s[2 * i]);
    int lo = hexVal(s[2 * i + 1]);
    if (hi < 0 || lo < 0) return false;
    out[i] = (uint8_t)((hi << 4) | lo);
  }
  return true;
}

int hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

void printHex(const uint8_t *data, uint8_t n) {
  const char *d = "0123456789abcdef";
  for (uint8_t i = 0; i < n; i++) {
    bt.write(d[data[i] >> 4]);
    bt.write(d[data[i] & 0x0F]);
  }
}

// Cifra, firma y envia un mensaje a la app
void sendSecure(const char *payload) {
  uint8_t n = (uint8_t)strlen(payload);
  if (n > MAX_PAYLOAD) return;
  uint8_t cipher[MAX_PAYLOAD];
  uint8_t mac[8];
  seqOut++;
  crypt('D', seqOut, (const uint8_t *)payload, cipher, n);
  computeMac('D', seqOut, cipher, n, mac);
  bt.print(F("E,D,"));
  bt.print((unsigned long)seqOut);
  bt.write(',');
  printHex(cipher, n);
  bt.write(',');
  printHex(mac, 8);
  bt.write('\n');
}

// ---------------- Actuadores y sensor ----------------
void setDoor(bool open) {
  if (open == doorOpen) return;
  doorOpen = open;
  servo.write(open ? ANGLE_OPEN : ANGLE_CLOSED);
  digitalWrite(PIN_LED, open ? HIGH : LOW);
  tone(PIN_BUZZER, open ? 2000 : 1000, 120);
}

uint16_t readDistanceCm() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);
  unsigned long dur = pulseIn(PIN_ECHO, HIGH, 25000UL);  // timeout ~4 m
  if (dur == 0) return 999;                               // sin eco
  return (uint16_t)(dur / 58UL);
}

void sendTelemetry() {
  char msg[MAX_PAYLOAD + 1];
  snprintf(msg, sizeof(msg), "D:%u,P:%u,M:%c,T:%lu,Q:%lu",
           (unsigned)distanceCm, doorOpen ? 1u : 0u, autoMode ? 'A' : 'M',
           telemetryPeriod, (unsigned long)lastSeqIn);
  sendSecure(msg);
  lastTelemetry = millis();
}

// ---------------- Ordenes ----------------
void runCommand(const char *cmd) {
  char ack[16];
  if (cmd[0] == '1' && cmd[1] == 0) {
    autoMode = false;
    setDoor(true);
  } else if (cmd[0] == '0' && cmd[1] == 0) {
    autoMode = false;
    setDoor(false);
  } else if (cmd[0] == 'A' && cmd[1] == 0) {
    autoMode = true;
    lastDetect = millis();
  } else if (cmd[0] == 'T') {
    for (const char *q = cmd + 1; *q; q++) {
      if (*q < '0' || *q > '9') { sendSecure("ERR:CMD"); return; }
    }
    long v = atol(cmd + 1);
    if (v < 500) v = 500;
    if (v > 60000) v = 60000;
    telemetryPeriod = (unsigned long)v;
  } else if (cmd[0] == '?' && cmd[1] == 0) {
    // Latido de la app: confirma que el enlace esta vivo (ya se actualizo lastValidRx). Responde solo telemetria.
    sendTelemetry();
    return;
  } else {
    sendSecure("ERR:CMD");
    return;
  }
  snprintf(ack, sizeof(ack), "ACK:%.8s", cmd);
  sendSecure(ack);
  sendTelemetry();
}

// Valida y procesa una linea recibida: E,A,<seq>,<cifrado>,<mac>
void handleLine(char *line) {
  if (line[0] != 'E' || line[1] != ',' || line[2] != 'A' || line[3] != ',') return;
  char *pSeq = line + 4;
  char *c1 = strchr(pSeq, ',');
  if (!c1) return;
  *c1 = 0;
  char *pCipher = c1 + 1;
  char *c2 = strchr(pCipher, ',');
  if (!c2) return;
  *c2 = 0;
  char *pMac = c2 + 1;

  size_t lenC = strlen(pCipher);
  if (lenC == 0 || (lenC & 1) || lenC > (size_t)MAX_PAYLOAD * 2) return;
  if (strlen(pMac) != 16) return;
  // seq: solo digitos, maximo 10 (cabe en 32 bits). Cualquier otra cosa se descarta.
  size_t lenS = strlen(pSeq);
  if (lenS == 0 || lenS > 10) return;
  for (size_t i = 0; i < lenS; i++) {
    if (pSeq[i] < '0' || pSeq[i] > '9') return;
  }
  uint32_t seq = strtoul(pSeq, NULL, 10);

  uint8_t n = (uint8_t)(lenC / 2);
  uint8_t cipher[MAX_PAYLOAD];
  uint8_t macRx[8];
  uint8_t macCalc[8];
  if (!fromHex(pCipher, cipher, n)) return;
  if (!fromHex(pMac, macRx, 8)) return;

  computeMac('A', seq, cipher, n, macCalc);
  if (!constTimeEqual(macCalc, macRx, 8)) return;   // firma invalida: se descarta en silencio
  if (seq <= lastSeqIn) return;                     // repeticion (replay): se descarta

  lastSeqIn = seq;
  lastValidRx = millis();

  uint8_t plain[MAX_PAYLOAD + 1];
  crypt('A', seq, cipher, plain, n);
  plain[n] = 0;
  runCommand((const char *)plain);
}

void pollBluetooth() {
  while (bt.available()) {
    char c = (char)bt.read();
    if (c == '\r') continue;
    if (c == '\n') {
      rxBuf[rxLen] = 0;
      if (rxLen > 0) handleLine(rxBuf);
      rxLen = 0;
    } else if (rxLen < sizeof(rxBuf) - 1) {
      rxBuf[rxLen++] = c;
    } else {
      rxLen = 0;  // desborde: descarta la linea
    }
  }
}

// ---------------- Arduino ----------------
void setup() {
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  servo.attach(PIN_SERVO);
  servo.write(ANGLE_CLOSED);
  bt.begin(9600);
  lastValidRx = millis();
  tone(PIN_BUZZER, 1500, 200);
}

void loop() {
  pollBluetooth();

  // Sensor cada 100 ms
  if (millis() - lastSensor >= 100) {
    lastSensor = millis();
    distanceCm = readDistanceCm();

    if (autoMode) {
      if (distanceCm > 0 && distanceCm <= DETECT_CM) {
        lastDetect = millis();
        setDoor(true);
      } else if (doorOpen && millis() - lastDetect > HOLD_OPEN_MS) {
        setDoor(false);
      }
    }
  }

  // Plan de contingencia: sin enlace valido en modo manual -> vuelve a automatico (autonomia local)
  if (!autoMode && millis() - lastValidRx > FAILSAFE_MS) {
    autoMode = true;
    lastDetect = millis();
    sendTelemetry();
  }

  // Telemetria periodica (cifrada)
  if (millis() - lastTelemetry >= telemetryPeriod) {
    sendTelemetry();
  }
}
