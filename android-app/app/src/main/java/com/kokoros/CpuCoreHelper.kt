package com.kokoros

import java.io.BufferedReader
import java.io.FileReader
import java.io.File
import java.io.IOException
import kotlin.math.max

object CpuCoreHelper {

    /**
     * Automatically detects the number of "Power" (Prime + Gold) cores.
     * Reads frequencies from /sys/devices/system/cpu/cpu[index]/cpufreq/cpuinfo_max_freq
     */
    fun getPowerCoreCount(): Int {
        val totalCores = Runtime.getRuntime().availableProcessors()
        val maxFrequencies = mutableListOf<Int>()

        for (i in 0 until totalCores) {
            val path = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
            try {
                val file = File(path)
                if (file.exists()) {
                    BufferedReader(FileReader(file)).use { reader ->
                        val line = reader.readLine()
                        if (line != null) {
                            maxFrequencies.add(line.trim().toInt())
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore sysfs access errors
            }
        }

        // Fallback if sysfs is blocked or empty
        if (maxFrequencies.isEmpty()) {
            return 2
        }

        // Find the absolute highest frequency on the chip (The Prime Core)
        val maxFreqOnChip = maxFrequencies.maxOrNull() ?: return 2

        // Define a "Power Core" as anything within 85% of the max speed.
        val powerCoreThreshold = (maxFreqOnChip * 0.85).toInt()

        var powerCoreCount = 0
        for (freq in maxFrequencies) {
            if (freq >= powerCoreThreshold) {
                powerCoreCount++
            }
        }

        // Ensure we return at least 1, but cap at total cores (sanity check)
        return powerCoreCount.coerceIn(1, totalCores)
    }
}
