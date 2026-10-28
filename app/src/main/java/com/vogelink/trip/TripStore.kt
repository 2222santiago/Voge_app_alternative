package com.vogelink.trip

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class TripRow(
    val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val odoStartKm: Float? = null,
    val odoEndKm: Float? = null,
    val gpsMeters: Double = 0.0,
    val maxSpeedKmh: Int = 0,
    val speedSum: Double = 0.0,
    val speedSamples: Int = 0,
    val maxLeanA: Int = 0,
    val maxLeanB: Int = 0,
    val maxRpm: Int = 0,
    val points: Int = 0,
) {
    val durationS: Long get() = (endMs - startMs) / 1000
    val averageSpeedKmh: Double get() = if (speedSamples > 0) speedSum / speedSamples else 0.0

    /** Distancia del odómetro de la moto si la hay; si no, la del GPS. */
    val distanceKm: Double
        get() {
            val odo = if (odoStartKm != null && odoEndKm != null) (odoEndKm - odoStartKm).toDouble() else null
            return if (odo != null && odo > 0) odo else gpsMeters / 1000
        }
}

data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double?,
    val speedKmh: Int?,
    val lean: Int?,
    val leanDirection: Int?,
    val rpm: Int?,
    val gear: Int?,
)

/** Notifica a la interfaz cuando cambian los viajes. */
object TripsHub {
    val revision = MutableStateFlow(0)
    val active = MutableStateFlow<TripRow?>(null)
}

class TripStore(context: Context) : SQLiteOpenHelper(context, "trips.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE trips(
                id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
                odo_start REAL, odo_end REAL, gps_m REAL NOT NULL DEFAULT 0, max_speed INTEGER NOT NULL DEFAULT 0,
                speed_sum REAL NOT NULL DEFAULT 0, speed_n INTEGER NOT NULL DEFAULT 0,
                max_lean_a INTEGER NOT NULL DEFAULT 0, max_lean_b INTEGER NOT NULL DEFAULT 0,
                max_rpm INTEGER NOT NULL DEFAULT 0, points INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE points(
                trip_id INTEGER NOT NULL, t_ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, alt REAL,
                speed INTEGER, lean INTEGER, lean_dir INTEGER, rpm INTEGER, gear INTEGER)"""
        )
        db.execSQL("CREATE INDEX points_trip ON points(trip_id, t_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun TripRow.values() = ContentValues().apply {
        put("start_ms", startMs)
        put("end_ms", endMs)
        put("odo_start", odoStartKm)
        put("odo_end", odoEndKm)
        put("gps_m", gpsMeters)
        put("max_speed", maxSpeedKmh)
        put("speed_sum", speedSum)
        put("speed_n", speedSamples)
        put("max_lean_a", maxLeanA)
        put("max_lean_b", maxLeanB)
        put("max_rpm", maxRpm)
        put("points", points)
    }

    fun insert(row: TripRow): TripRow {
        val id = writableDatabase.insert("trips", null, row.values())
        TripsHub.revision.value++
        return row.copy(id = id)
    }

    fun update(row: TripRow) {
        writableDatabase.update("trips", row.values(), "id = ?", arrayOf(row.id.toString()))
        TripsHub.revision.value++
    }

    fun delete(id: Long) {
        writableDatabase.delete("points", "trip_id = ?", arrayOf(id.toString()))
        writableDatabase.delete("trips", "id = ?", arrayOf(id.toString()))
        TripsHub.revision.value++
    }

    fun addPoint(tripId: Long, p: TrackPoint) {
        val v = ContentValues().apply {
            put("trip_id", tripId)
            put("t_ms", p.timeMs)
            put("lat", p.lat)
            put("lon", p.lon)
            put("alt", p.altitude)
            put("speed", p.speedKmh)
            put("lean", p.lean)
            put("lean_dir", p.leanDirection)
            put("rpm", p.rpm)
            put("gear", p.gear)
        }
        writableDatabase.insert("points", null, v)
    }

    fun list(): List<TripRow> = readableDatabase.rawQuery("SELECT * FROM trips ORDER BY start_ms DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.toTrip()) }
    }

    fun points(tripId: Long): List<TrackPoint> =
        readableDatabase.rawQuery("SELECT * FROM points WHERE trip_id = ? ORDER BY t_ms", arrayOf(tripId.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        TrackPoint(
                            timeMs = c.long("t_ms"),
                            lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
                            lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
                            altitude = c.doubleOrNull("alt"),
                            speedKmh = c.intOrNull("speed"),
                            lean = c.intOrNull("lean"),
                            leanDirection = c.intOrNull("lean_dir"),
                            rpm = c.intOrNull("rpm"),
                            gear = c.intOrNull("gear"),
                        )
                    )
                }
            }
        }

    private fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.int(col: String) = getInt(getColumnIndexOrThrow(col))
    private fun Cursor.intOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.doubleOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.floatOrNull(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getFloat(it) }

    private fun Cursor.toTrip() = TripRow(
        id = long("id"),
        startMs = long("start_ms"),
        endMs = long("end_ms"),
        odoStartKm = floatOrNull("odo_start"),
        odoEndKm = floatOrNull("odo_end"),
        gpsMeters = getDouble(getColumnIndexOrThrow("gps_m")),
        maxSpeedKmh = int("max_speed"),
        speedSum = getDouble(getColumnIndexOrThrow("speed_sum")),
        speedSamples = int("speed_n"),
        maxLeanA = int("max_lean_a"),
        maxLeanB = int("max_lean_b"),
        maxRpm = int("max_rpm"),
        points = int("points"),
    )

    companion object {
        private val ISO = DateTimeFormatter.ISO_INSTANT

        fun toGpx(trip: TripRow, points: List<TrackPoint>): String = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<gpx version="1.1" creator="VogeLink" xmlns="http://www.topografix.com/GPX/1/1">""")
            appendLine("  <trk><name>Viaje ${trip.id} ${ISO.format(Instant.ofEpochMilli(trip.startMs).atOffset(ZoneOffset.UTC))}</name><trkseg>")
            for (p in points) {
                append("""    <trkpt lat="${p.lat}" lon="${p.lon}">""")
                p.altitude?.let { append("<ele>$it</ele>") }
                append("<time>${ISO.format(Instant.ofEpochMilli(p.timeMs))}</time>")
                if (p.speedKmh != null || p.lean != null) {
                    append("<extensions>")
                    p.speedKmh?.let { append("<speed_kmh>$it</speed_kmh>") }
                    p.lean?.let { append("<lean>$it</lean>") }
                    p.leanDirection?.let { append("<lean_dir>$it</lean_dir>") }
                    p.rpm?.let { append("<rpm>$it</rpm>") }
                    p.gear?.let { append("<gear>$it</gear>") }
                    append("</extensions>")
                }
                appendLine("</trkpt>")
            }
            appendLine("  </trkseg></trk>")
            appendLine("</gpx>")
        }
    }
}
