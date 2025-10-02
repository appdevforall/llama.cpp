package com.example.llama

import android.content.Context
import android.os.BatteryManager

/**
 * A generic interface for a tool the model can use.
 * The result is always returned as a String.
 */
interface Tool {
    // The name of the tool
    val name: String

    // A description for future use in more advanced agent setups
    val description: String

    // Executes the tool's native code and returns the result as a string.
    fun execute(context: Context): String
}

/**
 * An implementation of a tool that gets the device's battery level.
 */
class BatteryTool : Tool {
    override val name: String = "get_device_battery"
    override val description: String = "Returns the current battery percentage of the device."

    override fun execute(context: Context): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // We format the result in a way the model can easily understand is system-provided data.
        return "[Tool Result for $name]: Device battery is at $batteryPct%."
    }
}
