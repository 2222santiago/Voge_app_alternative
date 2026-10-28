# Protocolo BLE cuadro TFT VOGE ↔ móvil

Fuente: análisis estático de `com.loncin.vogeglobal` 1.1.19 (versionCode 52), decompilado con jadx 1.5.6.
Nada de esto se ha verificado todavía contra una moto real.

## 1. Descubrimiento y conexión

1. El cuadro se empareja como Bluetooth clásico (perfil manos libres/HFP) con un nombre que empieza por `VOGE-`
   (`bluetooth/q.java:f`). La app toma ese nombre de `BluetoothHeadset.getConnectedDevices()`.
2. Lanza un escaneo BLE y se conecta al primer dispositivo cuyo nombre **contiene** ese nombre
   (sin distinguir mayúsculas) (`q.java:N0`).
3. `connectGatt(ctx, autoConnect=false, cb, TRANSPORT_LE)` → `discoverServices()` →
   `requestConnectionPriority(HIGH)` (`tb/e.java`).
4. **Sin autenticación, emparejamiento ni cifrado en la capa BLE.** Escribe en la característica en cuanto la encuentra.
5. Reconexión: hasta 100 intentos, uno cada 5 s.

### UUID (se comparan por *subcadena*; cubren varios fabricantes de cuadro)

| Papel | Subcadenas aceptadas |
|---|---|
| Servicio | `fe695f12-fd7b-4f9b-98cc-ee6cf57a776e`, `00003719-0000-1000-8000-00805f9b34fb`, `5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e`, `5456534d`, `0000fff0`, `0000b360` |
| Escritura | `6052202a-2928-4131-a2d0-456d5673ed2f`, `00005352`, `0000fff1` |
| Notificación | `ab9938d5-c354-4e2b-94f4-364e16ebcd33`, `00005354`, `0000fff2`, `0000b362`, `0000fff1` |

Las notificaciones se activan escribiendo `ENABLE_NOTIFICATION_VALUE` en **todos** los descriptores de la característica
de notificación. Las escrituras usan `writeCharacteristic` con el tipo de escritura por defecto de la característica.

## 2. Formato de trama (ambos sentidos)

Siempre **20 bytes**:

| Byte | Contenido |
|---|---|
| 0 | `0x01` |
| 1 | Comando |
| 2–17 | Datos |
| 18 | Checksum: XOR de los 20 bytes con el byte 18 a 0, es decir `XOR(b0..b17) ^ 0x04` |
| 19 | `0x04` |

Los enteros de varios bytes van en **big-endian** (`fd.d.i`). Las tramas recibidas que no midan 20 bytes se descartan.

## 3. Móvil → cuadro

### 0x5A (90) — Latido / sincronización de hora, cada 1000 ms mientras hay conexión (`BleBtConnectService.d`)

| Byte | Valor |
|---|---|
| 2 | `0` |
| 3 | Batería del móvil (%) |
| 4 | `50` (constante) |
| 5, 6, 7 | Hora, minuto y segundo locales (24 h) |
| 8 | `1` si es p. m., `0` si es a. m. |
| 9 | Bandera `qc.b.f27131c`; en esta versión nunca se pone a true → `0` |
| 10 | `2` (constante; probablemente plataforma o versión de protocolo) |

### Navegación (`bluetooth/w.java`)

Se envía como ráfaga `0x6A → 0x6B → 0x6C → 0x6D → 0x6E` con 100 ms entre tramas, como mucho una ráfaga cada 800 ms
(`BaseMapNaviActivity.G1`). Para terminar la navegación se envía un único `0x6A` con el byte 15 = 1.

#### 0x6A (106) — Maniobra y progreso

| Byte | Campo | Unidad |
|---|---|---|
| 2–4 | Distancia a la siguiente maniobra | m (24 bits BE) |
| 5–6 | Tiempo restante de ruta | s (16 bits BE) |
| 7–9 | Distancia restante de ruta | m (24 bits BE) |
| 10 | Icono de maniobra (tabla abajo) | |
| 11, 12 | Hora y minuto de llegada (24 h) | |
| 13 | Llegada: `1` p. m., `0` a. m. | |
| 14 | Tipo de vía: `0` normal, `1` túnel, `4` rotonda | |
| 15 | `1` = fin de navegación | |
| 16 | Salida de rotonda (tabla abajo) | |

**Icono de maniobra** (`mc/a.java:a`, a partir de `modifier`/`type` de Mapbox):

| Código | Maniobra |
|---|---|
| 1 | Recto / llegada / desconocida |
| 2 | Cambio de sentido (circulación por la derecha) |
| 3 | Cambio de sentido (circulación por la izquierda) |
| 4 | Izquierda |
| 5 | Derecha |
| 6 | Ligeramente a la izquierda (también bifurcación/rampa a la izquierda) |
| 7 | Ligeramente a la derecha (también bifurcación/rampa a la derecha) |
| 8 | Cerrada a la izquierda |
| 9 | Cerrada a la derecha |

**Salida de rotonda** (`mc/a.java:b`, a partir del ángulo `degrees` de la maniobra): sectores de 30°
15–45→1, 46–76→2, 78–108→3, 109–139→4, 140–170→5, 171–201→6, 202–232→7, 223–253→8, 254–284→9,
285–315→10, 316–345→11, 346–360→12; `+50` si se circula por la izquierda; `0` si no es rotonda.
(El original tiene los rangos 7 y 8 solapados y huecos de 1°: es un fallo de la app oficial.)

#### 0x6B–0x6E — Nombres de calle (UTF-8)

| Cmd | Contenido |
|---|---|
| 0x6B (107) | Calle siguiente, bytes 0–14 |
| 0x6C (108) | Calle siguiente, bytes 15–29 |
| 0x6D (109) | Calle actual, bytes 0–14 |
| 0x6E (110) | Calle actual, bytes 15–29 |

El byte 2 vale `1` si el idioma del móvil es chino y `0` en otro caso. El texto va en los bytes 3–17 (15 bytes).
El corte se hace sobre los bytes en bruto y puede partir un carácter UTF-8 multibyte.

## 4. Cuadro → móvil (notificaciones, `bluetooth/v.java:m`)

### 0x4A — Telemetría en vivo

| Byte | Campo | Notas |
|---|---|---|
| 2 | Velocidad | km/h (la app la pasa a mph si el byte 17 = 1) |
| 3–4 | Revoluciones | 16 bits BE |
| 5 | Marcha | |
| 6 | Velocidad media | |
| 7 | Ángulo de inclinación | |
| 8 | Lado de la inclinación | |
| 9 | Tensión de batería | ÷10 si protocolo ≥ 2 |
| 10 | Temperatura (bruta) | °C = bruto−60 (protocolo ≥ 2) o bruto−30; >250 = no válida. Nivel = bruto/50 o bruto/36 |
| 11 | Nivel de combustible | |
| 12 | Velocidad máxima | |
| 13 | Velocidad media histórica | |
| 14 | `signalRescue` | significado desconocido |
| 15 | Desconocido | se guarda pero no se usa |
| 16 | Versión de protocolo | |
| 17 | Unidad de distancia | `1` = millas |

### 0x4B — Parcial y odómetro

| Byte | Campo |
|---|---|
| 2–4 | Odómetro total (24 bits BE, ÷10 si protocolo ≥ 2) |
| 5–7 | Distancia parcial (misma escala) |
| 8–9 | Tiempo del parcial (16 bits BE; unidad sin confirmar) |
| 10 | Tiempo de 0 a 100, ÷10 s |

### 0x4C — Estados

Byte 2 = estado de navegación, byte 3 = estado del teléfono.

### 0x1A — Evento del cuadro

| Byte 2 | Efecto en la app |
|---|---|
| 2 | phoneState = 1 |
| 3 | phoneState = 2 |
| 4 | naviState = 2 |
| 5 | naviState = 1 |

Los estados de 0x4C/0x1A no se usan en ninguna lógica de la app (solo se guardan en `MotorInfoEntity`).

## 5. Notas sobre la app oficial

- `BleBtConnectService` es un servicio en primer plano `connectedDevice` con un WakeLock parcial de 200 minutos y
  reproduce en bucle un audio silencioso (`R.raw.navi_notify`) para que no maten el proceso.
- La navegación muere al bloquear el móvil: `MapboxNaviActivity` registra su observador de Mapbox como `onStarted`,
  y `onStop()` llama a `stopTripSession()`. `NaviService` (`foregroundServiceType=location`) está declarado pero no se usa.
- El «bloqueo» propio de la app es una capa negra con `screenBrightness = 0`.
