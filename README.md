# VogeLink

App Android alternativa a VOGE Global para enviar navegación al cuadro TFT de la moto y leer su telemetría.
El protocolo está en [`docs/protocolo_ble.md`](docs/protocolo_ble.md).

## Qué hace

- **Navegación al cuadro con el móvil bloqueado.** Lee las notificaciones de Google Maps (también Waze y OsmAnd)
  y las reenvía al cuadro. Un servicio en primer plano mantiene la conexión BLE, independiente de cualquier pantalla.
- **Panel de telemetría:** velocidad, revoluciones, marcha, inclinación, tensión, temperatura, combustible, odómetro, etc.
- **Viajes:** se graban solos mientras la moto está conectada (odómetro, velocidad máxima/media, inclinación máxima y
  trazado GPS), y se pueden exportar a GPX.
- **Latido 0x5A:** pone en hora el cuadro y le envía la batería del móvil.

## Compilar

```sh
export JAVA_HOME=~/Android/jdk-21 ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Primer uso

1. Desinstala o cierra la app oficial: dos apps conectadas a la vez al BLE del cuadro pueden pisarse.
2. Empareja el móvil con la moto como siempre (aparece como `VOGE-xxxx`).
3. Abre VogeLink → **Ajustes** → concede los permisos, el **acceso a notificaciones** y la exención de batería.
4. **Panel → Conectar.** Si no encuentra la moto sola, ve a Ajustes → *Buscar motos* y elígela (aparece marcada).
5. **Navegación → Prueba en el cuadro:** comprueba qué icono muestra el cuadro con cada código antes de salir.
6. Antes de cada salida, mira la tarjeta **Panel → Antes de salir**: inicia la ruta en Maps, vuelve a VogeLink y
   comprueba que todo está en verde (acceso, lector activo, «Leyendo Google Maps» con la maniobra y moto conectada).
   Si con la moto conectada Android desconecta el lector y no se recupera solo, salta un aviso.
7. Opcional: en **Ajustes → Al encender la moto**, VogeLink arranca y abre el Panel sola cuando el móvil se conecta
   por Bluetooth al cuadro. Para abrir el Panel desde segundo plano, Android pide el permiso «Mostrar sobre otras apps».
8. Navega con Google Maps. La primera vez, en **Navegación → Iconos sin identificar**, asigna cada flecha a su maniobra;
   después ya las reconoce solo.

Si algo no funciona, **Ajustes → Registro → Compartir**: incluye el log de BLE y las últimas notificaciones de navegación.
Activa antes *Registrar tramas en el log* para ver las tramas en hexadecimal.

## Estructura

| Paquete | Contenido |
|---|---|
| `protocol` | Tramas del cuadro (construcción y parseo). Kotlin puro, con tests. |
| `ble` | Cliente GATT (cola de operaciones, reconexión) y escáner. |
| `service` | `LinkService`: servicio en primer plano con latido, envío de navegación y grabación de viajes. |
| `nav` | Lector de notificaciones, parser es/en y memoria de iconos de maniobra. |
| `trip` | Viajes en SQLite y exportación GPX. |
| `ui` | Interfaz en Jetpack Compose. |
