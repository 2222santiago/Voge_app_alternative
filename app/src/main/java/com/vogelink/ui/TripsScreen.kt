package com.vogelink.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vogelink.trip.TripRow
import com.vogelink.trip.TripStore
import com.vogelink.trip.TripsHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")

@Composable
fun TripsScreen() {
    val ctx = LocalContext.current
    val store = remember { TripStore(ctx.applicationContext) }
    val revision by TripsHub.revision.collectAsStateWithLifecycle()
    val active by TripsHub.active.collectAsStateWithLifecycle()
    var trips by remember { mutableStateOf(emptyList<TripRow>()) }
    LaunchedEffect(revision) {
        trips = withContext(Dispatchers.IO) { store.list() }
    }
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<TripRow?>(null) }

    if (trips.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Todavía no hay viajes. Se graban solos mientras la moto está conectada.")
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(trips, key = { it.id }) { trip ->
            TripCard(
                trip = trip,
                recording = active?.id == trip.id,
                onShare = { scope.launch { shareGpx(ctx, store, trip) } },
                onDelete = { toDelete = trip },
            )
        }
    }

    toDelete?.let { trip ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar el viaje?") },
            text = { Text(formatDate(trip.startMs)) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch(Dispatchers.IO) { store.delete(trip.id) }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TripCard(trip: TripRow, recording: Boolean, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(formatDate(trip.startMs), style = MaterialTheme.typography.titleSmall)
                if (recording) Text("Grabando…", color = MaterialTheme.colorScheme.primary)
                Text(
                    "%.1f km · %s · máx. %d km/h · media %.0f km/h".format(
                        trip.distanceKm, formatDuration(trip.durationS), trip.maxSpeedKmh, trip.averageSpeedKmh,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Inclinación máx.: ${trip.maxLeanA}° / ${trip.maxLeanB}° · ${trip.maxRpm} rpm · ${trip.points} puntos GPS",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onShare, enabled = trip.points > 0) { Icon(Icons.Filled.Share, "Exportar GPX") }
            IconButton(onClick = onDelete, enabled = !recording) { Icon(Icons.Filled.Delete, "Borrar") }
        }
    }
}

private fun formatDate(ms: Long) = dateFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun formatDuration(s: Long) = if (s >= 3600) "%d h %02d min".format(s / 3600, (s % 3600) / 60) else "${s / 60} min"

private suspend fun shareGpx(ctx: Context, store: TripStore, trip: TripRow) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        File(dir, "viaje_${trip.id}.gpx").apply { writeText(TripStore.toGpx(trip, store.points(trip.id))) }
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/gpx+xml")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Exportar viaje"))
}
