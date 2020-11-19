package com.github.frozentear7.cloudoptimization

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.android.volley.*
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.time.LocalDateTime
import kotlin.coroutines.CoroutineContext


private const val TAG = "MainActivity"
private var DATA_PATH = Environment.getExternalStorageDirectory().toString() + "/Tess"
private const val lang = "eng"
private const val pdfFilename = "Raport projektu - CEA_Bus_Klemens_Mendroch.pdf"
private const val FILE_MANAGER_INTENT_CODE = 11
private const val SERVICE_URL = "https://cloud-optimization-server.herokuapp.com/ocr"

class MainActivity : AppCompatActivity() {
    private lateinit var requestQueue: RequestQueue
    private lateinit var pageImage: Bitmap
    private lateinit var root: File

    private lateinit var chargeCounterValueTextView: TextView
    private lateinit var capacityValueTextView: TextView
    private lateinit var chargeCounterChangeValueTextView: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var takePhotoButton: Button
    private lateinit var ocrResultTextView: TextView

    private lateinit var mBatteryManager: BatteryManager
    var prevChargeCounter = -1L
    var totalEnergyUsed = 0L

    private val uiContext: CoroutineContext = Dispatchers.Main

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestQueue = Volley.newRequestQueue(this)
        mBatteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        chargeCounterValueTextView = findViewById(R.id.chargeCounterValueTextView)
        capacityValueTextView = findViewById(R.id.capacityValueTextView)
        chargeCounterChangeValueTextView =
            findViewById(R.id.chargeCounterChangeValueTextView)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        takePhotoButton = findViewById(R.id.takePhotoButton)
        ocrResultTextView = findViewById(R.id.ocrResultTextView)

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
            intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(intent, FILE_MANAGER_INTENT_CODE)
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when(requestCode) {
            FILE_MANAGER_INTENT_CODE -> {
                val filename = data?.data

                if (filename != null)
                    processPdf(filename)
                else
                    ocrResultTextView.text = "Provide a valid PDF file"
            }
        }
    }

    private fun processPdf(filename: Uri) {
        val mode = 1

        Log.v(TAG, "Starting OCR")

        if (mode == 0) {
            // Local
            runPdfOCRLocal(filename)
        } else if (mode == 1) {
            // Cloud
            postPdfToCloudRequest(filename)
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

                    delay(60000)
                }
            }
        }
    }

    private fun runPdfOCRLocal(filename: Uri) {
        ocrResultTextView.text = ""
        root = this.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!

        PDFBoxResourceLoader.init(applicationContext)

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch {
            withContext(Dispatchers.Main) {
                val ocrResult = getOCRResult(filename)
                renderOCRResult(ocrResult)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun postPdfToCloudRequest(filename: Uri) {
        val postRequest = VolleyMultipartRequest(Request.Method.POST, SERVICE_URL,
            { response ->
                run {
                    val jsonResult = JSONObject(String(response.data))
                    val jobId = jsonResult.getString("job_id")

                    getOcrResultFromCloudRequest(jobId)
                }
            },
            { ocrResultTextView.text = "Failed sending the file to the cloud" },
            contentResolver.openInputStream(filename) as FileInputStream?
        )

        requestQueue.add(postRequest)
    }

    @SuppressLint("SetTextI18n")
    private fun getOcrResultFromCloudRequest(jobId: String) {
        val getRequest = StringRequest(
            Request.Method.GET, "$SERVICE_URL/$jobId",
            { response ->
                run {
                    val jsonResult = JSONObject(response)
                    val status = jsonResult.getString("status")

                    when {
                        jsonResult.has("error") -> {
                            ocrResultTextView.text = jsonResult.getString("error")
                        }
                        status == "IN_PROGRESS" -> {
//                            delay(5000)
                            getOcrResultFromCloudRequest(jobId)
                        }
                        status == "DONE" -> {
                            ocrResultTextView.text = jsonResult.getString("result")
                        }
                    }
                }
            },
            { ocrResultTextView.text = "Failed getting the OCR result from cloud" })

        requestQueue.add(getRequest)
    }

    private fun getOCRResult(filename: Uri): String {
        var ocrResult = ""

        val baseApi = TessBaseAPI()
        baseApi.setDebug(true)
        baseApi.init(DATA_PATH, lang)

//        val document: PDDocument = PDDocument.load(assets.open(pdfFilename)) // Local PDF in the assets directory
        val document: PDDocument = PDDocument.load(contentResolver.openInputStream(filename))
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

        document.close()
        baseApi.end()

        return ocrResult
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
            ocrResultTextView.text = if (ocrResultTextView.text.toString().isEmpty()
            ) recognizedText else ocrResultTextView.text.toString() + " " + recognizedText
        }
    }
}