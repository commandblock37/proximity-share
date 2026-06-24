package com.proximityshare.activities

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.proximityshare.MainViewModel
import com.proximityshare.R
import com.proximityshare.databinding.ActivityMainBinding
import com.proximityshare.databinding.ItemNearbyDeviceBinding
import com.proximityshare.managers.BleProximityManager
import com.proximityshare.models.NearbyDevice
import com.proximityshare.models.TransferState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var deviceAdapter: NearbyDeviceAdapter

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            initBle()
        } else {
            showPermissionRationale()
        }
    }

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onFileSelected(it)
            // If we already have a target from proximity trigger, go straight to transfer
            val target = viewModel.targetDevice.value
            if (target != null) {
                navigateToTransfer(target)
            }
        }
    }

    // Bluetooth enable launcher
    private val btEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissionsAndInit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        observeState()
        checkPermissionsAndInit()
    }

    private fun setupRecyclerView() {
        deviceAdapter = NearbyDeviceAdapter { device ->
            viewModel.clearProximityTrigger()
            onDeviceTapped(device)
        }
        binding.rvNearbyDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupClickListeners() {
        // Pick a file to send
        binding.btnPickFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        // Clear selected file
        binding.btnClearFile.setOnClickListener {
            viewModel.clearSelectedFile()
            updateFileUI(null)
        }

        // Manual rescan
        binding.fabRescan.setOnClickListener {
            viewModel.stopBle()
            viewModel.startBle()
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.nearbyDevices.collectLatest { devices ->
                deviceAdapter.submitList(devices.toMutableList())
                binding.tvDeviceCount.text = when (devices.size) {
                    0 -> "No devices nearby — bring phones together"
                    1 -> "1 device found"
                    else -> "${devices.size} devices found"
                }
                binding.emptyState.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                binding.rvNearbyDevices.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            viewModel.proximityEvent.collectLatest { device ->
                device?.let { showProximityDialog(it) }
            }
        }

        lifecycleScope.launch {
            viewModel.selectedFile.collectLatest { file ->
                updateFileUI(file?.second)
            }
        }

        lifecycleScope.launch {
            viewModel.incomingOffer.collectLatest { offer ->
                offer?.let {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Incoming File")
                        .setMessage("Someone wants to send you:\n\n${it.name}\n${it.sizeLabel}")
                        .setPositiveButton("Accept") { _, _ -> viewModel.acceptIncoming() }
                        .setNegativeButton("Decline") { _, _ -> viewModel.rejectIncoming() }
                        .setCancelable(false)
                        .show()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.transferState.collectLatest { state ->
                if (state == TransferState.RECEIVING || state == TransferState.SENDING) {
                    startActivity(Intent(this@MainActivity, TransferActivity::class.java))
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isScanning.collectLatest { scanning ->
                binding.scanningIndicator.visibility = if (scanning) View.VISIBLE else View.GONE
                binding.tvScanStatus.text = if (scanning) "Scanning for devices..." else "Scan paused"
            }
        }

        lifecycleScope.launch {
            viewModel.isAdvertising.collectLatest { advertising ->
                binding.tvDeviceName.text = if (advertising)
                    "You: ${viewModel.localDeviceName} (visible)"
                else
                    "You: ${viewModel.localDeviceName} (not visible)"
            }
        }
    }

    private fun showProximityDialog(device: NearbyDevice) {
        // Don't show if dialog is already up
        if (isFinishing || isDestroyed) return

        MaterialAlertDialogBuilder(this)
            .setTitle("📱 Device Nearby!")
            .setMessage(
                "${device.name} is touching your phone\n" +
                "Signal: ${device.rssi} dBm (${device.proximityLabel})\n\n" +
                "Pick a file to send?"
            )
            .setPositiveButton("Pick File") { _, _ ->
                viewModel.clearProximityTrigger()
                filePickerLauncher.launch("*/*")
            }
            .setNegativeButton("Dismiss") { _, _ ->
                viewModel.clearProximityTrigger()
            }
            .show()
    }

    private fun onDeviceTapped(device: NearbyDevice) {
        val file = viewModel.selectedFile.value
        if (file == null) {
            // No file selected yet — pick one first
            viewModel.clearProximityTrigger()
            Toast.makeText(this, "Pick a file to send to ${device.name}", Toast.LENGTH_SHORT).show()
            filePickerLauncher.launch("*/*")
        } else {
            // File ready — confirm and send
            MaterialAlertDialogBuilder(this)
                .setTitle("Send File")
                .setMessage("Send ${file.second} to ${device.name}?")
                .setPositiveButton("Send") { _, _ ->
                    navigateToTransfer(device)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun navigateToTransfer(device: NearbyDevice) {
        val intent = Intent(this, TransferActivity::class.java).apply {
            putExtra("DEVICE_ADDRESS", device.address)
            putExtra("DEVICE_NAME", device.name)
            putExtra("DEVICE_PORT", device.wifiPort)
        }
        startActivity(intent)
    }

    private fun updateFileUI(fileName: String?) {
        if (fileName != null) {
            binding.tvSelectedFile.text = "📎 $fileName"
            binding.tvSelectedFile.visibility = View.VISIBLE
            binding.btnClearFile.visibility = View.VISIBLE
            binding.tvFileHint.visibility = View.GONE
        } else {
            binding.tvSelectedFile.visibility = View.GONE
            binding.btnClearFile.visibility = View.GONE
            binding.tvFileHint.visibility = View.VISIBLE
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ─────────────────────────────────────────────────────────────

    private fun checkPermissionsAndInit() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        when {
            needed.isEmpty() -> initBle()
            else -> permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            perms += listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        return perms
    }

    private fun initBle() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (!btAdapter.isEnabled) {
            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        viewModel.startBle()
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "ProximityShare needs Bluetooth and Location permissions to discover nearby devices.\n\n" +
                "Please grant these in Settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        viewModel.startBle()
    }

    override fun onPause() {
        super.onPause()
        // Keep advertising/scanning in background via service (optional enhancement)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopBle()
    }

    // ─────────────────────────────────────────────────────────────
    // RECYCLER ADAPTER
    // ─────────────────────────────────────────────────────────────

    inner class NearbyDeviceAdapter(
        private val onDeviceClick: (NearbyDevice) -> Unit
    ) : RecyclerView.Adapter<NearbyDeviceAdapter.DeviceViewHolder>() {

        private val items = mutableListOf<NearbyDevice>()

        fun submitList(newItems: MutableList<NearbyDevice>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val binding = ItemNearbyDeviceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return DeviceViewHolder(binding)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class DeviceViewHolder(
            private val itemBinding: ItemNearbyDeviceBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(device: NearbyDevice) {
                itemBinding.tvDeviceName.text = device.name
                itemBinding.tvRssi.text = "${device.rssi} dBm"
                itemBinding.tvProximityLabel.text = device.proximityLabel
                itemBinding.progressSignal.progress = device.proximityPercent

                // Color code by proximity
                val colorRes = when {
                    device.rssi >= BleProximityManager.RSSI_VERY_CLOSE -> R.color.proximity_touch
                    device.rssi >= BleProximityManager.RSSI_CLOSE -> R.color.proximity_close
                    else -> R.color.proximity_near
                }
                itemBinding.signalDot.setBackgroundColor(
                    ContextCompat.getColor(itemView.context, colorRes)
                )

                itemBinding.root.setOnClickListener { onDeviceClick(device) }
            }
        }
    }
}
