package com.ric.humanwmaps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ric.humanwmaps.geopdf.GeoPdfMetadata
import com.ric.humanwmaps.geopdf.GeoPdfParser
import com.ric.humanwmaps.tracking.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { HumanWApp() } }
    }
}

@Composable
private fun HumanWApp() {
    val context = LocalContext.current
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var tracking by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf<Location?>(null) }

    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pdfUri = uri
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val allowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (allowed) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START))
            tracking = true
        }
    }

    LiveLocationEffect { location = it }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("HumanW Maps", style = MaterialTheme.typography.headlineMedium)
            Text("Offline GeoPDF navigation + GPS tracking")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openPdf.launch(arrayOf("application/pdf")) }) {
                    Text(if (pdfUri == null) "Import GeoPDF" else "Change Map")
                }
                Button(onClick = {
                    if (tracking) {
                        context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
                        tracking = false
                    } else if (hasLocationPermission(context)) {
                        ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START))
                        tracking = true
                    } else {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }) { Text(if (tracking) "Stop Track" else "Start Track") }
            }

            LocationCard(location, tracking)

            pdfUri?.let { GeoPdfMap(it, location) } ?: Card(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.fillMaxWidth().size(360.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { Text("Import a GeoPDF to begin") }
            }
        }
    }
}

@Composable
private fun LocationCard(location: Location?, tracking: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (tracking) "● Recording" else "GPS ready")
            Text("Latitude: ${location?.latitude?.let { "%.6f".format(it) } ?: "—"}")
            Text("Longitude: ${location?.longitude?.let { "%.6f".format(it) } ?: "—"}")
            Text("Accuracy: ${location?.accuracy?.let { "±%.1f m".format(it) } ?: "—"}")
            Text("Altitude: ${if (location?.hasAltitude() == true) "%.1f m".format(location.altitude) else "—"}")
            Text("Speed: ${if (location?.hasSpeed() == true) "%.1f km/h".format(location.speed * 3.6f) else "—"}")
        }
    }
}

@Composable
private fun GeoPdfMap(uri: Uri, location: Location?) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { renderFirstPage(context, uri) }
    }
    val metadata by produceState<GeoPdfMetadata?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { GeoPdfParser.parse(context, uri) }.getOrNull() }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                bitmap == null -> Box(Modifier.fillMaxWidth().size(360.dp), contentAlignment = Alignment.Center) { Text("Rendering map…") }
                else -> ZoomableMap(bitmap!!, metadata, location)
            }
            Text(
                when {
                    metadata == null -> "PDF loaded • no supported GeoPDF viewport metadata found"
                    else -> "GeoPDF active • GPS overlay enabled${metadata?.crsName?.let { " • $it" } ?: ""}"
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ZoomableMap(bitmap: Bitmap, metadata: GeoPdfMetadata?, location: Location?) {
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var height by remember { mutableFloatStateOf(1f) }
    val marker = if (metadata != null && location != null) metadata.gpsToPageNormalized(location.latitude, location.longitude) else null
    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { clip = true }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = tx
                    translationY = ty
                })
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        tx += pan.x
                        ty += pan.y
                    }
                }
                .onSizeChanged { width = it.width.toFloat(); height = it.height.toFloat() }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "GeoPDF map",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
            marker?.let { (nx, ny) ->
                if (nx in -0.2f..1.2f && ny in -0.2f..1.2f) {
                    Box(
                        modifier = Modifier
                            .padding(0.dp)
                            .size(18.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .graphicsLayer {
                                translationX = nx * width - size.width / 2f
                                translationY = ny * height - size.height / 2f
                            }
                    )
                }
            }
        }
    }
}

private fun renderFirstPage(context: Context, uri: Uri): Bitmap? {
    var pfd: ParcelFileDescriptor? = null
    return try {
        pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        PdfRenderer(pfd).use { renderer ->
            renderer.openPage(0).use { page ->
                val scale = 2
                Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888).also { bitmap ->
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    } catch (_: Exception) { null } finally { runCatching { pfd?.close() } }
}

@Composable
private fun LiveLocationEffect(onLocation: (Location) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        if (!hasLocationPermission(context)) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { onLocation(it) }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, 1000L, 1f, listener) }
        }
        onDispose { runCatching { manager.removeUpdates(listener) } }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
