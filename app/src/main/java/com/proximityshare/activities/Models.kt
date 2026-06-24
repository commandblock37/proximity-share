package com.proximityshare.models

import android.bluetooth.BluetoothDevice

data class NearbyDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val wifiPort: Int,
    val bluetoothDevice: BluetoothDevice?,
    val lastSeen: Long = System.currentTimeMillis(),
    var wifiDirectAddress: String? = null
) {
    val proximityLabel: String get() = when {
        rssi >= -30 -> "Touching"
        rssi >= -45 -> "Very Close"
        rssi >= -60 -> "Close"
        rssi >= -70 -> "Nearby"
        else -> "Far"
    }

    val proximityPercent: Int get() = when {
        rssi >= -20 -> 100
        rssi <= -90 -> 0
        else -> ((rssi + 90) * 100 / 70).coerceIn(0, 100)
    }

    val signalBars: Int get() = when {
        rssi >= -40 -> 4
        rssi >= -55 -> 3
        rssi >= -65 -> 2
        rssi >= -75 -> 1
        else -> 0
    }
}

data class TransferFile(
    val name: String,
    val size: Long,
    val mimeType: String
) {
    val sizeLabel: String get() {
        return when {
            size >= 1_073_741_824 -> "%.1f GB".format(size / 1_073_741_824.0)
            size >= 1_048_576 -> "%.1f MB".format(size / 1_048_576.0)
            size >= 1024 -> "%.1f KB".format(size / 1024.0)
            else -> "$size B"
        }
    }
}

data class TransferProgress(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val isReceiving: Boolean,
    val savedPath: String? = null
) {
    val percent: Int get() = if (totalBytes > 0)
        ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100)
    else 0

    val speedLabel: String get() = when {
        speedBytesPerSec >= 1_048_576 -> "%.1f MB/s".format(speedBytesPerSec / 1_048_576.0)
        speedBytesPerSec >= 1024 -> "%.0f KB/s".format(speedBytesPerSec / 1024.0)
        else -> "$speedBytesPerSec B/s"
    }

    val etaLabel: String get() {
        val remaining = totalBytes - bytesTransferred
        if (speedBytesPerSec <= 0) return "Calculating..."
        val seconds = remaining / speedBytesPerSec
        return when {
            seconds < 60 -> "${seconds}s remaining"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s remaining"
            else -> "~${seconds / 3600}h remaining"
        }
    }
}

enum class TransferState {
    IDLE,
    WAITING,       // Server: listening
    CONNECTING,    // Client: dialing
    OFFERING,      // Client: sent metadata, awaiting accept
    AWAITING_ACCEPT,  // Server: showing accept dialog to user
    SENDING,       // Client: streaming bytes
    RECEIVING,     // Server: writing bytes
    COMPLETE,
    REJECTED,
    ERROR
}
