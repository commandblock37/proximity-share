package com.proximityshare

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.proximityshare.managers.BleProximityManager
import com.proximityshare.managers.WifiDirectTransferManager
import com.proximityshare.models.NearbyDevice
import com.proximityshare.models.TransferProgress
import com.proximityshare.models.TransferState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Random

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val context: Context get() = getApplication()

    val bleManager = BleProximityManager(context)
    val transferManager = WifiDirectTransferManager(context)

    // Unique name for this device session
    val localDeviceName: String = generateFriendlyName()

    // Transfer server port (randomized to avoid conflicts)
    val serverPort: Int = 8988 + Random().nextInt(100)

    // Expose BLE state
    val nearbyDevices: StateFlow<List<NearbyDevice>> = bleManager.nearbyDevices
    val proximityEvent: StateFlow<NearbyDevice?> = bleManager.proximityEvent
    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    val isAdvertising: StateFlow<Boolean> = bleManager.isAdvertising

    // Expose transfer state
    val transferState: StateFlow<TransferState> = transferManager.transferState
    val transferProgress: StateFlow<TransferProgress?> = transferManager.transferProgress
    val incomingOffer = transferManager.incomingOffer

    // Currently selected file to send
    private val _selectedFile = MutableStateFlow<Triple<Uri, String, Long>?>(null)
    val selectedFile: StateFlow<Triple<Uri, String, Long>?> = _selectedFile

    // Which device the user tapped to send to
    private val _targetDevice = MutableStateFlow<NearbyDevice?>(null)
    val targetDevice: StateFlow<NearbyDevice?> = _targetDevice

    init {
        // Start the transfer server immediately on launch
        transferManager.startServer(serverPort)

        // Watch for proximity trigger — auto-prompt file picker when devices touch
        viewModelScope.launch {
            bleManager.proximityEvent.collect { device ->
                if (device != null) {
                    Log.d(TAG, "Proximity trigger: ${device.name} @ ${device.rssi} dBm")
                    _targetDevice.value = device
                }
            }
        }
    }

    fun startBle() {
        if (bleManager.isBluetoothEnabled()) {
            bleManager.startScanning()
            bleManager.startAdvertising(localDeviceName, serverPort)
        }
    }

    fun stopBle() {
        bleManager.stopScanning()
        bleManager.stopAdvertising()
    }

    fun onFileSelected(uri: Uri) {
        val (name, size) = queryFileInfo(uri)
        _selectedFile.value = Triple(uri, name, size)
        Log.d(TAG, "File selected: $name ($size bytes)")
    }

    fun sendFileTo(device: NearbyDevice) {
        val file = _selectedFile.value ?: return
        val address = device.wifiDirectAddress

        if (address.isNullOrEmpty()) {
            Log.e(TAG, "No Wi-Fi Direct address for ${device.name}")
            return
        }

        val (uri, name, size) = file
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        Log.d(TAG, "Sending $name to ${device.name} @ $address:${device.wifiPort}")
        transferManager.sendFile(address, device.wifiPort, uri, name, size, mimeType)
    }

    fun sendFileToAddress(address: String, port: Int) {
        val file = _selectedFile.value ?: return
        val (uri, name, size) = file
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        transferManager.sendFile(address, port, uri, name, size, mimeType)
    }

    fun acceptIncoming() = transferManager.acceptIncomingTransfer()
    fun rejectIncoming() = transferManager.rejectIncomingTransfer()

    fun clearProximityTrigger() = bleManager.clearProximityEvent()
    fun clearTargetDevice() { _targetDevice.value = null }
    fun clearSelectedFile() { _selectedFile.value = null }

    fun resetTransfer() {
        transferManager.resetState()
        _selectedFile.value = null
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying file info", e)
        }
        return Pair(name, size)
    }

    private fun generateFriendlyName(): String {
        val adjectives = listOf("Swift", "Bold", "Quick", "Bright", "Sharp", "Cool", "Fast", "Blue")
        val nouns = listOf("Fox", "Bear", "Hawk", "Wolf", "Lion", "Tiger", "Eagle", "Lynx")
        return "${adjectives.random()} ${nouns.random()}"
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.cleanup()
        transferManager.cleanup()
    }
}
