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

class MainActivity : AppCompatActivity() {
    private lateinit var pageImage: Bitmap
    private lateinit var root: File
    private lateinit var ocrResultEditText: EditText
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val chargeCounterValueTextView = findViewById<TextView>(R.id.chargeCounterValueTextView)
        val capacityValueTextView = findViewById<TextView>(R.id.capacityValueTextView)
        val chargeCounterChangeValueTextView =
            findViewById<TextView>(R.id.chargeCounterChangeValueTextView)
        val currentTimeTextView = findViewById<TextView>(R.id.currentTimeTextView)
        val takePhotoButton = findViewById<Button>(R.id.takePhotoButton)

        ocrResultEditText = findViewById(R.id.ocrResultEditText)

        val mBatteryManager: BatteryManager =
            getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var prevChargeCounter = -1L
        var totalEnergyUsed = 0L

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
                //GZIPInputStream gin = new GZIPInputStream(in);
                val out: OutputStream = FileOutputStream(
                    DATA_PATH
                            + "tessdata/" + lang + ".traineddata"
                )

                // Transfer bytes from in to out
                val buf = ByteArray(1024)
                var len: Int
                //while ((lenf = gin.read(buff)) > 0) {
                while (`in`.read(buf).also { len = it } > 0) {
                    out.write(buf, 0, len)
                }
                `in`.close()
                //gin.close();
                out.close()
                Log.v(TAG, "Copied $lang traineddata")
            } catch (e: IOException) {
                Log.e(TAG, "Was unable to copy $lang traineddata $e")
            }
        }

        // Setup energy counters
        scope.launch {
            withContext(Dispatchers.Main) {
                while (true) {
                    val current = LocalDateTime.now()
                    println("Current Date and Time is: $current")
                    currentTimeTextView.text = current.toString()

                    val chargeCounter: Long =
                        mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    Log.i(TAG, "Remaining battery capacity = $chargeCounter uAh")
                    chargeCounterValueTextView.text = chargeCounter.toString()

//                    val currentNow: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
//                    Log.i(TAG, "Instantaneous battery current $currentNow uA")

//                    val currentAvg: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
//                    Log.i(TAG, "Average battery current = $currentAvg uA")

                    val capacity: Long =
                        mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    Log.i(TAG, "Remaining battery capacity = $capacity %")
                    capacityValueTextView.text = capacity.toString()

                    if (prevChargeCounter != -1L)
                        totalEnergyUsed += prevChargeCounter - chargeCounter
                    prevChargeCounter = chargeCounter

                    Log.i(TAG, "Total energy used = $totalEnergyUsed uAh")
                    chargeCounterChangeValueTextView.text = totalEnergyUsed.toString()

//                    val energyCounter: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
//                    Log.i(TAG, "Remaining energy = $energyCounter nWh")

                    delay(10000)
                }
            }
        }

        takePhotoButton.setOnClickListener {
            Log.v(TAG, "Starting Camera app")
            runPdfOCR()
        }
    }

    private fun runPdfOCR() {
        PDFBoxResourceLoader.init(applicationContext)

        GlobalScope.launch {
            // Render the page and save it to an image file
            try {
                // Load in an already created PDF
                val document: PDDocument =
                    PDDocument.load(assets.open("Raport projektu - CEA_Bus_Klemens_Mendroch.pdf"))
                // Create a renderer for the document
                val renderer = PDFRenderer(document)
                // Render the image to an RGB Bitmap
                pageImage = renderer.renderImage(0, 1f, Bitmap.Config.RGB_565)

                // Save the render result to an image
                root =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                val path = root.absolutePath + "/render.jpg"
                val renderFile = File(path)
                val fileOut = FileOutputStream(renderFile)
                pageImage.compress(Bitmap.CompressFormat.JPEG, 100, fileOut)
                fileOut.close()

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
                } catch (e: IOException) {
                    Log.e(TAG, "Couldn't correct orientation: $e")
                }

                // _image.setImageBitmap( bitmap );
                Log.v(TAG, "Before baseApi")
                val baseApi = TessBaseAPI()
                baseApi.setDebug(true)
                baseApi.init(DATA_PATH, lang)
                baseApi.setImage(bitmap)
                var recognizedText = baseApi.utF8Text
                baseApi.end()

                // You now have the text in recognizedText var, you can do anything with it.
                // We will display a stripped out trimmed alpha-numeric version of it (if lang is eng)
                // so that garbage doesn't make it to the display.
                Log.v(TAG, "OCRED TEXT: $recognizedText")
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
            } catch (e: IOException) {
                Log.e(TAG, "Exception thrown while rendering file", e)
            }
        }
    }
}