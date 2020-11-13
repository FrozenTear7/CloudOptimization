package com.github.frozentear7.cloudoptimization

import android.content.Context
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mBatteryManager: BatteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val chargeCounter: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        Log.i(TAG, "Remaining battery capacity = $chargeCounter uAh")

        val currentNow: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        Log.i(TAG, "Instantaneous battery current $currentNow uA")

        val currentAvg: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        Log.i(TAG, "Average battery current = $currentAvg uA")

        val capacity: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        Log.i(TAG, "Remaining battery capacity = $capacity %")

        val energyCounter: Long = mBatteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        Log.i(TAG, "Remaining energy = $energyCounter nWh")
    }
}