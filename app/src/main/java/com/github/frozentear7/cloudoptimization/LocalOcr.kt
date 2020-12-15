package com.github.frozentear7.cloudoptimization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Environment
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

private var DATA_PATH = Environment.getExternalStorageDirectory().toString() + "/Tess"
private const val lang = "eng"

class LocalOcr(applicationContext: Context) {
    private lateinit var pageImage: Bitmap
    private var root: File

    private fun saveResultImage(pageImage: Bitmap, path: String) {
        val renderFile = File(path)
        val fileOut = FileOutputStream(renderFile)
        pageImage.compress(Bitmap.CompressFormat.JPEG, 100, fileOut)
        fileOut.close()
    }

    private fun processImageForOCR(path: String): Bitmap? {
        val options = BitmapFactory.Options()
        options.inSampleSize = 4

        var bitmap = BitmapFactory.decodeFile(path, options)
        try {
            val exif = ExifInterface(path)
            val exifOrientation: Int = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            var rotate = 0
            when (exifOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotate = 90
                ExifInterface.ORIENTATION_ROTATE_180 -> rotate = 180
                ExifInterface.ORIENTATION_ROTATE_270 -> rotate = 270
            }
            if (rotate != 0) {

                // Getting width & height of the given image.
                val w = bitmap.width
                val h = bitmap.height

                // Setting pre rotate
                val mtx = Matrix()
                mtx.preRotate(rotate.toFloat())

                // Rotating Bitmap
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, w, h, mtx, false)
            }

            // Convert to ARGB_8888, required by tess
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

            return bitmap
        } catch (e: IOException) {
            return null
        }
    }

    fun runOCR(pdfFile: FileInputStream): String {
        val baseApi = TessBaseAPI()
        baseApi.setDebug(true)
        baseApi.init(DATA_PATH, lang)

        var ocrResult = ""

        val document: PDDocument = PDDocument.load(pdfFile)
        val renderer = PDFRenderer(document)

        // Render the image to an RGB Bitmap
        for (i in 0 until document.numberOfPages) {
            pageImage = renderer.renderImage(i, 10f, Bitmap.Config.RGB_565)

            // Save the render result to an image
            val path = root.absolutePath + "/render$i.jpg"
            saveResultImage(pageImage, path)
            val processedImage = processImageForOCR(path)

            if (processedImage != null) {
                baseApi.setImage(processedImage)
                ocrResult += baseApi.utF8Text + "\n"
            }
        }

        document.close()
        baseApi.end()
        pdfFile.close()

        if (lang.equals("eng", ignoreCase = true)) {
            ocrResult = ocrResult.replace("[^a-zA-Z0-9]+".toRegex(), " ")
        }
        ocrResult = ocrResult.trim { it <= ' ' }

        return ocrResult
    }

    init {
        PDFBoxResourceLoader.init(applicationContext)
        root = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
    }
}