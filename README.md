# HumanW Maps

Android offline field-navigation app inspired by GeoPDF/Avenza-style workflows.

## MVP

- Import PDF/GeoPDF from Android Storage Access Framework
- Offline first-page PDF rendering
- Live GPS readout
- Foreground/background track recording
- CSV track persistence per session
- Simple field-oriented Compose UI

## Next milestones

1. Parse GeoPDF geospatial dictionaries (`/VP`, `/Measure`, GPTS/LPTS) and CRS metadata.
2. Map WGS84 GPS coordinates into PDF page coordinates.
3. Multi-page/tiled PDF rendering with pinch/zoom/pan.
4. Waypoints, measurements, layers and track history.
5. GPX/KML/GeoJSON export.
6. Elevation, gradient, lean angle, G-force and ride replay.

## Stack

- Kotlin
- Jetpack Compose
- Android PdfRenderer
- Android LocationManager
- Foreground location service
- AGP 9.3 / compileSdk 37

> Important: the current MVP renders PDFs and records GPS, but does **not** yet georeference GPS on top of a GeoPDF. That requires parsing the GeoPDF coordinate metadata and applying its transformation correctly.
