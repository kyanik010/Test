package com.example.telemetry

import android.os.Debug
import android.os.Process
import android.os.SystemClock

data class SystemTelemetrySnapshot(
    val cpuUsagePercent: Int = 0,
    val totalPssMb: Int = 0,
    val nativeHeapMb: Int = 0,
    val javaHeapMb: Int = 0
)

/**
 * Measures real process CPU% and memory footprint using Android Process/Debug APIs.
 */
class SystemTelemetryMonitor {
    private var lastWallMs = SystemClock.elapsedRealtime()
    private var lastCpuMs = Process.getElapsedCpuTime()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun sample(): SystemTelemetrySnapshot {
        val nowWall = SystemClock.elapsedRealtime()
        val nowCpu = Process.getElapsedCpuTime()
        val deltaWall = (nowWall - lastWallMs).coerceAtLeast(1L)
        val deltaCpu = (nowCpu - lastCpuMs).coerceAtLeast(0L)
        lastWallMs = nowWall
        lastCpuMs = nowCpu

        val rawPercent = ((deltaCpu.toDouble() / (deltaWall.toDouble() * cores)) * 100.0)
            .toInt()
            .coerceIn(0, 100)

        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        val totalPssMb = (memInfo.totalPss / 1024).coerceAtLeast(0)
        val nativeHeapMb = (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt().coerceAtLeast(0)
        val rt = Runtime.getRuntime()
        val javaHeapMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt().coerceAtLeast(0)

        return SystemTelemetrySnapshot(
            cpuUsagePercent = rawPercent,
            totalPssMb = totalPssMb,
            nativeHeapMb = nativeHeapMb,
            javaHeapMb = javaHeapMb
        )
    }
}
