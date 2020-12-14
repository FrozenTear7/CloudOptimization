package com.github.frozentear7.cloudoptimization

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.util.*
import kotlin.random.Random.Default.nextInt


private const val TAG = "MainActivity"
private const val ML_TAG = "TrainingData"
private var DATA_PATH = Environment.getExternalStorageDirectory().toString() + "/Tess"
private const val lang = "eng"
private const val FILE_MANAGER_INTENT_CODE = 11
private const val SERVICE_URL = "https://cloud-optimization-server.herokuapp.com/ocr"
private const val repeatJobs = 1
private const val pdfFilename = "I2RM_4_PMendroch.pdf"

class MainActivity : AppCompatActivity() {
    private lateinit var requestQueue: RequestQueue

    private lateinit var chargeCounterValueTextView: TextView
    private lateinit var capacityValueTextView: TextView
    private lateinit var chargeCounterChangeValueTextView: TextView
    private lateinit var currentTimeTextView: TextView
    private lateinit var uploadPdfButton: Button
    private lateinit var ocrResultTextView: TextView

    private lateinit var mBatteryManager: BatteryManager
    private var prevChargeCounter = -1L
    private var totalEnergyUsed = 0L

    private val mainActivityScope = CoroutineScope(SupervisorJob())

    private var startTime = Date()
    private var endTime = Date()
    private var startBatteryCapacity = 0L
    private var endBatteryCapacity = 0L
    private var numberOfPages = 0

    private val df = DecimalFormat("#.##")

    // File size utils
    private val File.sizeInMb get() = if (!exists()) 0.0 else length().toDouble() / (1024 * 1024)

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
        uploadPdfButton = findViewById(R.id.uploadPdfButton)
        ocrResultTextView = findViewById(R.id.ocrResultTextView)

        // Create tessdata trained data directories on the phone
        createTessdataDir()
        copyTessdata()

        // Setup energy counters
        printEnergyStatus()

        uploadPdfButton.setOnClickListener {
            intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(intent, FILE_MANAGER_INTENT_CODE)
        }

//        processPdf(Uri.fromFile(File("test")))

        df.roundingMode = RoundingMode.CEILING
    }

    private fun createTessdataDir() {
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
    }

    private fun copyTessdata() {
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
    }

    @SuppressLint("SetTextI18n")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
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
        // Initially just randomize the location of service
        var processMode = nextInt(2)

        if (processMode == 0)
            Log.v(TAG, "Starting OCR locally")
        else if (processMode == 1)
            Log.v(TAG, "Starting OCR in cloud")

        uploadPdfButton.isEnabled = false

        // Start timer
        startTime = Date()
        startBatteryCapacity =
            mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val document: PDDocument = PDDocument.load(contentResolver.openInputStream(filename) as FileInputStream)
        numberOfPages = document.numberOfPages

        mainActivityScope.launch(Dispatchers.IO) {
            processMode = 0
            if (processMode == 0) {
                // Local
                runPdfOCRLocal(filename)
            } else if (processMode == 1) {
                // Cloud
                postPdfToCloudRequest(filename)
            }
        }
    }

    private fun printEnergyStatus() {
        mainActivityScope.launch(Dispatchers.Main) {
            while (true) {
                val current = LocalDateTime.now()
                val chargeCounter: Long =
                    mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                val capacity: Long =
                    mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                if (prevChargeCounter != -1L)
                    totalEnergyUsed += prevChargeCounter - chargeCounter
                prevChargeCounter = chargeCounter

//                Log.i(TAG, "Current Date and Time is: $current")
//                Log.i(TAG, "Remaining battery capacity = $chargeCounter uAh")
//                Log.i(TAG, "Remaining battery capacity = $capacity %")

                currentTimeTextView.text = current.toString()
                chargeCounterValueTextView.text = chargeCounter.toString()
                capacityValueTextView.text = capacity.toString()
                chargeCounterChangeValueTextView.text = totalEnergyUsed.toString()

                delay(60000)
            }
        }
    }

    private suspend fun logProcessdata(filename: Uri, mode: String) {
        endTime = Date()
        endBatteryCapacity =
            mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val totalTime = df.format((endTime.time - startTime.time))
        val batteryChange = endBatteryCapacity - startBatteryCapacity
        val pdfFile = File(filename.path!!)
        val fileSize = pdfFile.sizeInMb * repeatJobs

        Log.v(ML_TAG, "$mode, $totalTime, $fileSize, $batteryChange, $numberOfPages")

        withContext(Dispatchers.Main) {
            uploadPdfButton.isEnabled = true
        }
    }

    private suspend fun runPdfOCRLocal(filename: Uri) {
        val localOcrService = LocalOcr(applicationContext)

        for (i in 1..repeatJobs) {
            ocrResultTextView.text = ""
            Log.i(TAG, "Running job $i out of $repeatJobs")

            try {
//                val ocrResult = localOcrService.runOCR(assets.open(pdfFilename) as FileInputStream)
                val ocrResult = localOcrService.runOCR(contentResolver.openInputStream(filename) as FileInputStream)
//
                withContext(Dispatchers.Main) {
                    ocrResultTextView.text = ocrResult
                }
            } catch (e: IOException) {
                Log.e(TAG, "Exception thrown while rendering file", e)
                uploadPdfButton.isEnabled = true
            }
        }

        logProcessdata(filename, "local")
    }

    @SuppressLint("SetTextI18n")
    private fun postPdfToCloudRequest(filename: Uri) {
        for (i in 1..repeatJobs) {
            ocrResultTextView.text = ""
            Log.i(TAG, "Running job $i out of $repeatJobs")
            val postRequest = VolleyMultipartRequest(
                Request.Method.POST, SERVICE_URL,
                { response ->
                    run {
                        val jsonResult = JSONObject(String(response.data))
                        val jobId = jsonResult.getString("job_id")

                        getOcrResultFromCloudRequest(jobId, filename)
                    }
                },
                {
                    ocrResultTextView.text = "Failed sending the file to the cloud"
                    uploadPdfButton.isEnabled = true
                },
//                assets.open(pdfFilename) as FileInputStream
                contentResolver.openInputStream(filename) as FileInputStream?
            )

            requestQueue.add(postRequest)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun getOcrResultFromCloudRequest(jobId: String, filename: Uri) {
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
                            mainActivityScope.launch(Dispatchers.IO) {
                                delay(5000)
                                getOcrResultFromCloudRequest(jobId, filename)
                            }
                        }
                        status == "DONE" -> {
                            ocrResultTextView.text = jsonResult.getString("result")

                            mainActivityScope.launch(Dispatchers.Main) {
                                logProcessdata(filename, "cloud")
                            }
                        }
                    }
                }
            },
            {
                ocrResultTextView.text = "Failed getting the OCR result from cloud"
                uploadPdfButton.isEnabled = true
            })

        requestQueue.add(getRequest)
    }
}