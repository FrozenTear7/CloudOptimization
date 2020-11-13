package com.github.frozentear7.cloudoptimization

import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.time.LocalDateTime

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val chargeCounterValueTextView = findViewById<TextView>(R.id.chargeCounterValueTextView)
        val capacityValueTextView = findViewById<TextView>(R.id.capacityValueTextView)
        val chargeCounterChangeValueTextView =
            findViewById<TextView>(R.id.chargeCounterChangeValueTextView)
        val currentTimeTextView = findViewById<TextView>(R.id.currentTimeTextView)

        val mBatteryManager: BatteryManager =
            getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val scope = CoroutineScope(Dispatchers.Default)
        var prevChargeCounter = -1L
        var totalEnergyUsed = 0L

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

                    Log.i(TAG, "Total energy used = $totalEnergyUsed %")
                    chargeCounterChangeValueTextView.text = totalEnergyUsed.toString()

//                    val energyCounter: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
//                    Log.i(TAG, "Remaining energy = $energyCounter nWh")

                    delay(10000)
                }
            }
        }
    }
}