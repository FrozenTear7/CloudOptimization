package com.github.frozentear7.cloudoptimization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import kotlinx.coroutines.*
import java.io.*
import java.time.LocalDateTime


private const val TAG = "MainActivity"
private var DATA_PATH = Environment.getExternalStorageDirectory().toString() + "/Tess"
private const val lang = "eng"
private const val pdfFilename = "Raport projektu - CEA_Bus_Klemens_Mendroch.pdf"

class MainActivity : AppCompatActivity() {
    private lateinit var pageImage: Bitmap
    private lateinit var root: File

    private lateinit var chargeCounterValueTextView: TextView
    private lateinit var capacityValueTextView: TextView
    private lateinit var chargeCounterChangeValueTextView: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var takePhotoButton: Button
    private lateinit var ocrResultEditText: EditText

    private lateinit var mBatteryManager: BatteryManager
    var prevChargeCounter = -1L
    var totalEnergyUsed = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mBatteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        chargeCounterValueTextView = findViewById(R.id.chargeCounterValueTextView)
        capacityValueTextView = findViewById(R.id.capacityValueTextView)
        chargeCounterChangeValueTextView =
            findViewById(R.id.chargeCounterChangeValueTextView)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        takePhotoButton = findViewById(R.id.takePhotoButton)
        ocrResultEditText = findViewById(R.id.ocrResultEditText)

        // Create tessdata trained data directories on the phone
        val paths = arrayOf(DATA_PATH, "$DATA_PATH/tessdata/")

        for (path in paths) {
            val dir = File(path)
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.v(TAG, "ERROR: Creation of directory $path on sdcard failed")
                    return
                } else {
                    Log.v(TAG, "Created directory $path on sdcard")
                }
            }
        }

        if (!File("$DATA_PATH/tessdata/$lang.traineddata").exists()) {
            try {
                val assetManager = assets
                val `in`: InputStream = assetManager.open("tessdata/$lang.traineddata")
                val out: OutputStream = FileOutputStream(
                    DATA_PATH
                            + "tessdata/" + lang + ".traineddata"
                )

                // Transfer bytes from in to out
                val buf = ByteArray(1024)
                var len: Int
                while (`in`.read(buf).also { len = it } > 0) {
                    out.write(buf, 0, len)
                }
                `in`.close()
                out.close()
                Log.v(TAG, "Copied $lang traineddata")
            } catch (e: IOException) {
                Log.e(TAG, "Was unable to copy $lang traineddata $e")
            }
        }

        // Setup energy counters
        printEnergyStatus()

        takePhotoButton.setOnClickListener {
            Log.v(TAG, "Starting OCR")
            runPdfOCR()
        }
    }

    private fun printEnergyStatus() {
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            withContext(Dispatchers.Main) {
                while (true) {
                    val current = LocalDateTime.now()
                    val chargeCounter: Long =
                        mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    val currentNow: Long =
                        mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    val currentAvg: Long =
                        mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                    val capacity: Long =
                        mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val energyCounter: Long =
                        mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)

                    if (prevChargeCounter != -1L)
                        totalEnergyUsed += prevChargeCounter - chargeCounter
                    prevChargeCounter = chargeCounter

                    Log.i(TAG, "Current Date and Time is: $current")
                    Log.i(TAG, "Remaining battery capacity = $chargeCounter uAh")
                    Log.i(TAG, "Instantaneous battery current $currentNow uA")
                    Log.i(TAG, "Average battery current = $currentAvg uA")
                    Log.i(TAG, "Remaining battery capacity = $capacity %")
                    Log.i(TAG, "Total energy used = $totalEnergyUsed uAh")
                    Log.i(TAG, "Remaining energy = $energyCounter nWh")

                    currentTimeTextView.text = current.toString()
                    chargeCounterValueTextView.text = chargeCounter.toString()
                    capacityValueTextView.text = capacity.toString()
                    chargeCounterChangeValueTextView.text = totalEnergyUsed.toString()

                    delay(10000)
                }
            }
        }
    }

    private fun runPdfOCR() {
        ocrResultEditText.setText("")
        root =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        val baseApi = TessBaseAPI()
        baseApi.setDebug(true)
        baseApi.init(DATA_PATH, lang)

        PDFBoxResourceLoader.init(applicationContext)

        var ocrResult = ""

        val document: PDDocument = PDDocument.load(assets.open(pdfFilename))
        val renderer = PDFRenderer(document)

        try {
            // Render the image to an RGB Bitmap
            for (i in 0 until document.numberOfPages) {
                pageImage = renderer.renderImage(i, 1f, Bitmap.Config.RGB_565)

                // Save the render result to an image
                val path = root.absolutePath + "/render$i.jpg"
                saveResultImage(pageImage, path)
                val processedImage = processImageForOCR(path)

                if (processedImage != null) {
                    Log.v(TAG, "Call OCR service")
                    baseApi.setImage(processedImage)
                    ocrResult += baseApi.utF8Text + "\n"
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Exception thrown while rendering file", e)
        }

        renderOCRResult(ocrResult)
        baseApi.end()
    }

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
            Log.v(TAG, "Orient: $exifOrientation")
            var rotate = 0
            when (exifOrientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotate = 90
                ExifInterface.ORIENTATION_ROTATE_180 -> rotate = 180
                ExifInterface.ORIENTATION_ROTATE_270 -> rotate = 270
            }
            Log.v(TAG, "Rotation: $rotate")
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
            Log.e(TAG, "Couldn't correct orientation: $e")
            return null
        }
    }

    private fun renderOCRResult(ocrResult: String) {
        var recognizedText = ocrResult

        // Put recognized text into the EditText content
        Log.v(TAG, "OCR result: $recognizedText")
        if (lang.equals("eng", ignoreCase = true)) {
            recognizedText = recognizedText.replace("[^a-zA-Z0-9]+".toRegex(), " ")
        }
        recognizedText = recognizedText.trim { it <= ' ' }
        if (recognizedText.isNotEmpty()) {
            ocrResultEditText.setText(
                if (ocrResultEditText.text.toString()
                        .isEmpty()
                ) recognizedText else ocrResultEditText.text.toString() + " " + recognizedText
            )
            ocrResultEditText.setSelection(ocrResultEditText.text.toString().length)
        }
    }
}