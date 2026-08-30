package com.ric.humanwmaps.geopdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlin.math.abs

/** Parsed geospatial metadata for the first page of a GeoPDF. */
data class GeoPdfMetadata(
    val pageWidth: Float,
    val pageHeight: Float,
    val viewportBBox: FloatArray,
    val gpts: DoubleArray,
    val lpts: DoubleArray,
    val crsName: String?,
    val transform: ProjectiveTransform
) {
    /**
     * Converts WGS84 latitude/longitude to normalized page coordinates (0..1, top-left origin).
     * GPTS is defined as latitude/longitude pairs, while LPTS is local normalized viewport coordinates.
     */
    fun gpsToPageNormalized(latitude: Double, longitude: Double): Pair<Float, Float>? {
        val local = transform.map(longitude, latitude) ?: return null
        val lx = local.first
        val ly = local.second
        val x0 = viewportBBox[0]
        val y0 = viewportBBox[1]
        val x1 = viewportBBox[2]
        val y1 = viewportBBox[3]
        val pageX = x0 + lx.toFloat() * (x1 - x0)
        val pageYBottom = y0 + ly.toFloat() * (y1 - y0)
        val nx = pageX / pageWidth
        val ny = 1f - (pageYBottom / pageHeight)
        if (!nx.isFinite() || !ny.isFinite()) return null
        return nx to ny
    }
}

object GeoPdfParser {
    fun parse(context: Context, uri: Uri): GeoPdfMetadata? {
        PDFBoxResourceLoader.init(context.applicationContext)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        PDDocument.load(bytes).use { document ->
            if (document.numberOfPages == 0) return null
            val page = document.getPage(0)
            val pageWidth = page.cropBox.width
            val pageHeight = page.cropBox.height
            val dict = page.cosObject

            val vpArray = deref(dict.getDictionaryObject(COSName.getPDFName("VP"))) as? COSArray ?: return null
            if (vpArray.size() == 0) return null
            val viewport = deref(vpArray.getObject(0)) as? COSDictionary ?: return null
            val bbox = numberArray(viewport.getDictionaryObject(COSName.BBOX)) ?: return null
            if (bbox.size < 4) return null

            val measure = deref(viewport.getDictionaryObject(COSName.getPDFName("Measure"))) as? COSDictionary ?: return null
            val gpts = numberArray(measure.getDictionaryObject(COSName.getPDFName("GPTS"))) ?: return null
            val lpts = numberArray(measure.getDictionaryObject(COSName.getPDFName("LPTS"))) ?: return null
            if (gpts.size < 8 || lpts.size < 8 || gpts.size != lpts.size) return null

            val count = minOf(gpts.size, lpts.size) / 2
            val src = Array(count) { i ->
                val lat = gpts[i * 2]
                val lon = gpts[i * 2 + 1]
                lon to lat
            }
            val dst = Array(count) { i -> lpts[i * 2] to lpts[i * 2 + 1] }
            val transform = ProjectiveTransform.fit(src, dst) ?: return null

            val gcs = deref(measure.getDictionaryObject(COSName.getPDFName("GCS"))) as? COSDictionary
            val crs = gcs?.getString(COSName.getPDFName("WKT"))
                ?: gcs?.getString(COSName.getPDFName("EPSG"))
                ?: gcs?.getNameAsString(COSName.getPDFName("Type"))

            return GeoPdfMetadata(
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                viewportBBox = floatArrayOf(bbox[0].toFloat(), bbox[1].toFloat(), bbox[2].toFloat(), bbox[3].toFloat()),
                gpts = gpts,
                lpts = lpts,
                crsName = crs,
                transform = transform
            )
        }
    }

    private fun deref(base: COSBase?): COSBase? = if (base is COSObject) base.`object` else base

    private fun numberArray(base: COSBase?): DoubleArray? {
        val array = deref(base) as? COSArray ?: return null
        val out = DoubleArray(array.size())
        for (i in 0 until array.size()) {
            val value = deref(array.getObject(i)) as? COSNumber ?: return null
            out[i] = value.doubleValue()
        }
        return out
    }
}

/** 2D projective transform (homography), solved from four or more control-point pairs. */
data class ProjectiveTransform(private val h: DoubleArray) {
    fun map(x: Double, y: Double): Pair<Double, Double>? {
        val d = h[6] * x + h[7] * y + 1.0
        if (abs(d) < 1e-12) return null
        return ((h[0] * x + h[1] * y + h[2]) / d) to
            ((h[3] * x + h[4] * y + h[5]) / d)
    }

    companion object {
        fun fit(src: Array<Pair<Double, Double>>, dst: Array<Pair<Double, Double>>): ProjectiveTransform? {
            if (src.size < 4 || src.size != dst.size) return null
            // Use the first four control points. GeoPDF GPTS/LPTS commonly provides viewport corners.
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i]
                val (u, v) = dst[i]
                val r = i * 2
                a[r][0] = x; a[r][1] = y; a[r][2] = 1.0
                a[r][6] = -u * x; a[r][7] = -u * y; a[r][8] = u
                a[r + 1][3] = x; a[r + 1][4] = y; a[r + 1][5] = 1.0
                a[r + 1][6] = -v * x; a[r + 1][7] = -v * y; a[r + 1][8] = v
            }
            val solution = gaussianElimination(a) ?: return null
            return ProjectiveTransform(solution)
        }

        private fun gaussianElimination(m: Array<DoubleArray>): DoubleArray? {
            val n = 8
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
                if (abs(m[pivot][col]) < 1e-12) return null
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
                val div = m[col][col]
                for (j in col until n + 1) m[col][j] /= div
                for (row in 0 until n) {
                    if (row == col) continue
                    val factor = m[row][col]
                    for (j in col until n + 1) m[row][j] -= factor * m[col][j]
                }
            }
            return DoubleArray(n) { m[it][n] }
        }
    }
}
