# HumanW Maps

HumanW Maps is an **offline-only Android field-navigation app** built around GeoPDF, local GNSS/GPS tracking, and on-device storage.

## Offline guarantee

The installed app is designed to work with airplane mode enabled after the map files are already on the device.

Runtime rules:

- No `android.permission.INTERNET`
- No `ACCESS_NETWORK_STATE`
- No online basemap or tile server
- No cloud account, API key, telemetry, analytics, ads, or remote database
- GeoPDF parsing and rendering happen on-device
- GPS/GNSS position comes from the Android location subsystem
- Tracks, waypoints, settings, and exports stay in local storage
- CI rejects forbidden network permissions and common runtime networking/map SDK dependencies

Build-time dependency downloads from Maven/Gradle are allowed; this does not create a runtime internet dependency in the APK.

## Current MVP

- Import PDF/GeoPDF using Android Storage Access Framework
- Offline PDF rendering
- GeoPDF metadata parsing (`/VP`, `/Measure`, `/GPTS`, `/LPTS`, `/BBox`)
- WGS84 GPS-to-page transform for supported geospatial PDFs
- Live GPS marker overlay
- Pinch zoom and pan
- Live latitude, longitude, accuracy, altitude, and speed
- Foreground/background track recording
- Incremental CSV track persistence per session

## Offline data flow

```text
Local GeoPDF/PDF
      ↓
Android Storage Access Framework
      ↓
PDFBox + PdfRenderer (on-device)
      ↓
GeoPDF coordinate transform
      ↑
Android GNSS/GPS
      ↓
TrackingService
      ↓
Local track files / future local database
```

## Next milestones

1. Persistent map library: copy imported GeoPDF files into app-managed local storage.
2. Live track polyline over the GeoPDF and follow-GPS mode.
3. Waypoints, notes, categories, and locally stored geotagged photos.
4. Distance/area measurement and offline layers.
5. Local track/history database with crash-safe recovery.
6. GPX/KML/GeoJSON/CSV import/export using the Android document picker.
7. Offline elevation/gradient from recorded GNSS altitude; optional local DEM support later.
8. Compass, lean angle, G-force, acceleration timer, and ride replay.
9. Proper CRS projection support for GeoPDFs that are not directly expressed in WGS84 geographic coordinates.

## Stack

- Kotlin
- Jetpack Compose
- Android PdfRenderer
- PDFBox Android
- Android LocationManager / GNSS
- Foreground location service
- Local Android storage
- AGP 9.3 / compileSdk 37

## GPS note

GNSS itself does not require mobile data or Wi-Fi. With no network assistance, a cold GPS fix can take longer depending on the device, sky visibility, and satellite data already cached by Android. Once a fix is available, HumanW Maps does not require internet connectivity for navigation or recording.
