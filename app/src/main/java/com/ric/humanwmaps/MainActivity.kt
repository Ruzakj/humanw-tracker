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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ric.humanwmaps.tracking.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HumanWApp()
            }
        }
    }
}

@Composable
private fun HumanWApp() {
    val context = LocalContext.current
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    var tracking by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf<Location?>(null) }

    val openPdf = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pdfUri = uri
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (allowed) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START)
            )
            tracking = true
        }
    }

    LiveLocationEffect { location = it }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("HumanW Maps", style = MaterialTheme.typography.headlineMedium)
            Text("Offline PDF field navigation + GPS tracker")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openPdf.launch(arrayOf("application/pdf")) }) {
                    Text(if (pdfUri == null) "Import PDF" else "Change PDF")
                }

                Button(onClick = {
                    if (tracking) {
                        context.startService(
                            Intent(context, TrackingService::class.java)
                                .setAction(TrackingService.ACTION_STOP)
                        )
                        tracking = false
                    } else if (hasLocationPermission(context)) {
                        ContextCompat.startForegroundService(
                            context,
                            Intent(context, TrackingService::class.java)
                                .setAction(TrackingService.ACTION_START)
                        )
                        tracking = true
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }) {
                    Text(if (tracking) "Stop Track" else "Start Track")
                }
            }

            LocationCard(location = location, tracking = tracking)

            pdfUri?.let { uri ->
                PdfPreview(uri)
            } ?: Card(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Import a PDF / GeoPDF to begin")
                }
            }

            Text(
                "GeoPDF georeferencing is intentionally not enabled yet. " +
                    "The next phase will parse the PDF geospatial metadata and transform WGS84 GPS coordinates into PDF page coordinates."
            )
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
private fun PdfPreview(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { renderFirstPage(context, uri) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        if (bitmap == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(360.dp),
                contentAlignment = Alignment.Center
            ) { Text("Rendering PDF…") }
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "PDF map preview",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
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
                val bitmap = Bitmap.createBitmap(
                    page.width * scale,
                    page.height * scale,
                    Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { pfd?.close() }
    }
}

@Composable
private fun LiveLocationEffect(onLocation: (Location) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        if (!hasLocationPermission(context)) return@DisposableEffect onDispose { }

        val manager = context.getSystemService(LocationManager::class.java)
        val listener = LocationListener { onLocation(it) }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        providers.forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, 1000L, 1f, listener) }
        }

        onDispose { runCatching { manager.removeUpdates(listener) } }
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
