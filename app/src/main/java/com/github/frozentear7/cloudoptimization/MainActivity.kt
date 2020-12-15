package com.github.frozentear7.cloudoptimization

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.provider.OpenableColumns
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
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.util.*
import kotlin.random.Random.Default.nextInt


private const val TAG = "MainActivity"
private const val ML_TAG = "TrainingData"
private var LOGS_PATH = Environment.getExternalStorageDirectory().toString() + "/TrainingDataLogs"
private const val FILE_MANAGER_INTENT_CODE = 11

//private const val SERVICE_URL = "https://cloud-optimization-server.herokuapp.com/ocr"
private const val SERVICE_URL = "http://192.168.100.10:8080/ocr"
private const val repeatJobs = 50

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

    private var cloudJobsDone = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitNetwork().build())

        requestQueue = Volley.newRequestQueue(this)
        mBatteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        chargeCounterValueTextView = findViewById(R.id.chargeCounterValueTextView)
        capacityValueTextView = findViewById(R.id.capacityValueTextView)
        chargeCounterChangeValueTextView =
            findViewById(R.id.chargeCounterChangeValueTextView)
        currentTimeTextView = findViewById(R.id.currentTimeTextView)
        uploadPdfButton = findViewById(R.id.uploadPdfButton)
        ocrResultTextView = findViewById(R.id.ocrResultTextView)

        // Setup energy counters
        printEnergyStatus()

        df.roundingMode = RoundingMode.CEILING

        uploadPdfButton.setOnClickListener {
            intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(intent, FILE_MANAGER_INTENT_CODE)
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
        val processMode = nextInt(2)

        if (processMode == 0)
            Log.v(TAG, "Starting OCR locally")
        else if (processMode == 1)
            Log.v(TAG, "Starting OCR in cloud")

        uploadPdfButton.isEnabled = false

        // Start timer
        startTime = Date()
        startBatteryCapacity =
            mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val document: PDDocument =
            PDDocument.load(contentResolver.openInputStream(filename) as FileInputStream)
//        val document: PDDocument = PDDocument.load(contentResolver.openInputStream(Uri.parse(pdfFilename)) as FileInputStream)
        numberOfPages = document.numberOfPages
        document.close()

        if (processMode == 0) {
            // Local
            runPdfOCRLocal(filename)
        } else if (processMode == 1) {
            // Cloud
            postPdfToCloudRequest(filename)
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

    private fun logProcessData(filename: Uri, mode: String) {
        endTime = Date()
        endBatteryCapacity =
            mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val totalTime = df.format((endTime.time - startTime.time) / 1000.0)
        val batteryChange = endBatteryCapacity - startBatteryCapacity

        val fileSize = filename.let { contentResolver.query(it, null, null, null, null) }
            ?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()
                df.format(cursor.getLong(sizeIndex) / (1024.0 * 1024.0))
            }

        Log.v(
            ML_TAG,
            "mode: $mode, totalTime: $totalTime, fileSize: $fileSize, batteryChange: $batteryChange, numberOfPages: $numberOfPages, repeatJobs: $repeatJobs"
        )

        val trainingDataFile = File(LOGS_PATH, "trainingData.txt")

        try {
            FileOutputStream(trainingDataFile, true).bufferedWriter().use { writer ->
                writer.append("$mode, $totalTime, $fileSize, $batteryChange, $numberOfPages, $repeatJobs\n")
            }
        } catch (e: Exception) {
            Log.v(TAG, "Exception while writing data logs: $e")
        }
    }

    private fun runPdfOCRLocal(filename: Uri) {
        ocrResultTextView.text = ""
        mainActivityScope.launch(Dispatchers.IO) {
            val localOcrService = LocalOcr(applicationContext)

            for (i in 1..repeatJobs) {
                Log.i(TAG, "Running job $i out of $repeatJobs")

                try {
//                val ocrResult = localOcrService.runOCR(assets.open(pdfFilename) as FileInputStream)
                    val ocrResult =
                        localOcrService.runOCR(contentResolver.openInputStream(filename) as FileInputStream)
//
                    withContext(Dispatchers.Main) {
                        ocrResultTextView.text = ocrResult
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Exception thrown while rendering file", e)

                    withContext(Dispatchers.Main) {
                        uploadPdfButton.isEnabled = true
                    }
                }
            }

            logProcessData(filename, "local")
            withContext(Dispatchers.Main) {
                uploadPdfButton.isEnabled = true
                // Continue the loop for testing
                processPdf(filename)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun postPdfToCloudRequest(filename: Uri) {
        mainActivityScope.launch(Dispatchers.IO) {
            ocrResultTextView.text = ""
            Log.i(TAG, "Running job $cloudJobsDone out of $repeatJobs")
            val postRequest = VolleyMultipartRequest(
                Request.Method.POST, SERVICE_URL,
                { response ->
                    run {
                        val jsonResult = JSONObject(String(response.data))
                        val jobId = jsonResult.getString("job_id")

                        getOcrResultFromCloudRequest(jobId, filename)
                    }
                },
                { error ->
                    run {
                        Log.e(TAG, "Exception thrown while posting the file to the cloud: ", error)
                        ocrResultTextView.text = "Failed sending the file to the cloud"
                        uploadPdfButton.isEnabled = true
                    }
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
                    try {
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
                                cloudJobsDone += 1

                                if (cloudJobsDone == repeatJobs) {
                                    logProcessData(filename, "cloud")
                                    uploadPdfButton.isEnabled = true
                                    // Continue the loop for testing
                                    processPdf(filename)
                                } else {
                                    postPdfToCloudRequest(filename)
                                }
                            }
                        }
                    } catch (e: JSONException) {
                        Log.e(TAG, "Exception thrown while parsing a response", e)
                        ocrResultTextView.text = "Failed getting the OCR result from cloud"
                        uploadPdfButton.isEnabled = true
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