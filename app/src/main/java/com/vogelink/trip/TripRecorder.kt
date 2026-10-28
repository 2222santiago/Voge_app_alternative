package com.vogelink.trip

import android.location.Location
import com.vogelink.core.LinkHub
import com.vogelink.protocol.Telemetry
import com.vogelink.protocol.TripCounters

/**
 * Registra un viaje mientras la moto está conectada. Si se reconecta en menos de [RESUME_MS]
 * (un repostaje, un corte de BLE) continúa el mismo viaje.
 * Todos los métodos son @Synchronized: se llaman desde el hilo de BLE y desde el de ubicación.
 */
class TripRecorder(private val store: TripStore) {

    companion object {
        private const val RESUME_MS = 5 * 60_000L
        private const val FLUSH_MS = 10_000L
        private const val MAX_ACCURACY_M = 30f
    }

    private var row: TripRow? = null
    private var connected = false
    private var lastSeenMs = 0L
    private var lastFlushMs = 0L
    private var lastLocation: Location? = null
    private var lastTelemetry: Telemetry? = null

    @Synchronized
    fun onConnected(now: Long) {
        connected = true
        val current = row
        if (current != null && now - lastSeenMs < RESUME_MS) {
            lastSeenMs = now
            return
        }
        finishLocked()
        row = store.insert(TripRow(startMs = now, endMs = now))
        lastSeenMs = now
        lastFlushMs = now
        lastLocation = null
        TripsHub.active.value = row
        LinkHub.log("Viaje iniciado (#${row?.id})")
    }

    @Synchronized
    fun onDisconnected(now: Long) {
        connected = false
        lastSeenMs = now
        flushLocked(now)
    }

    @Synchronized
    fun onTelemetry(t: Telemetry, now: Long) {
        val r = row ?: return
        lastTelemetry = t
        lastSeenMs = now
        var updated = r.copy(
            endMs = now,
            maxSpeedKmh = maxOf(r.maxSpeedKmh, t.speedKmh),
            maxRpm = maxOf(r.maxRpm, t.rpm),
        )
        if (t.speedKmh > 0) updated = updated.copy(speedSum = r.speedSum + t.speedKmh, speedSamples = r.speedSamples + 1)
        updated = if (t.leanDirection == 0) {
            updated.copy(maxLeanA = maxOf(r.maxLeanA, t.leanAngle))
        } else {
            updated.copy(maxLeanB = maxOf(r.maxLeanB, t.leanAngle))
        }
        row = updated
        maybeFlush(now)
    }

    @Synchronized
    fun onCounters(c: TripCounters, now: Long) {
        val r = row ?: return
        row = r.copy(endMs = now, odoStartKm = r.odoStartKm ?: c.totalKm, odoEndKm = c.totalKm)
        maybeFlush(now)
    }

    @Synchronized
    fun onLocation(loc: Location, recordPoint: Boolean) {
        val r = row ?: return
        if (!connected || (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M)) return
        val prev = lastLocation
        val added = prev?.distanceTo(loc)?.toDouble() ?: 0.0
        lastLocation = loc
        row = r.copy(gpsMeters = r.gpsMeters + added, endMs = loc.time, points = r.points + if (recordPoint) 1 else 0)
        if (recordPoint) {
            val t = lastTelemetry
            store.addPoint(
                r.id,
                TrackPoint(
                    timeMs = loc.time,
                    lat = loc.latitude,
                    lon = loc.longitude,
                    altitude = if (loc.hasAltitude()) loc.altitude else null,
                    speedKmh = t?.speedKmh,
                    lean = t?.leanAngle,
                    leanDirection = t?.leanDirection,
                    rpm = t?.rpm,
                    gear = t?.gear,
                ),
            )
        }
        maybeFlush(loc.time)
    }

    /** Llamado periódicamente: cierra el viaje si la moto lleva demasiado tiempo desconectada. */
    @Synchronized
    fun tick(now: Long) {
        if (row != null && !connected && now - lastSeenMs > RESUME_MS) finishLocked()
    }

    @Synchronized
    fun finish() = finishLocked()

    private fun maybeFlush(now: Long) {
        if (now - lastFlushMs >= FLUSH_MS) flushLocked(now)
    }

    private fun flushLocked(now: Long) {
        val r = row ?: return
        store.update(r)
        lastFlushMs = now
        TripsHub.active.value = r
    }

    private fun finishLocked() {
        val r = row ?: return
        row = null
        TripsHub.active.value = null
        val trivial = r.durationS < 60 && r.gpsMeters < 100 && r.distanceKm < 0.1
        if (trivial) {
            store.delete(r.id)
            LinkHub.log("Viaje #${r.id} descartado (demasiado corto)")
        } else {
            store.update(r)
            LinkHub.log("Viaje #${r.id} guardado: %.1f km".format(r.distanceKm))
        }
    }
}
