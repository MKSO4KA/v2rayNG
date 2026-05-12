package com.v2ray.ang.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.v2ray.ang.R
import com.v2ray.ang.dto.NearbyPackageDto
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.GzipUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class NearbySharingActivity : HelperBaseActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnSend: Button
    private lateinit var btnReceive: Button
    private lateinit var layoutQr: LinearLayout
    private lateinit var ivQrCode: ImageView
    private lateinit var tvToken: TextView

    private val connectionsClient by lazy { Nearby.getConnectionsClient(this) }
    private val strategy = Strategy.P2P_STAR
    private val serviceId = "com.v2ray.ang.NEARBY_SYNC"

    private var currentMode = 0 // 0=idle, 1=sending, 2=receiving
    private var pairingToken: String = ""
    private var targetToken: String = ""

    private var pendingAction: (() -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(this, "Missing required permissions for Nearby Share", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_share)

        tvStatus = findViewById(R.id.tvStatus)
        btnSend = findViewById(R.id.btnSend)
        btnReceive = findViewById(R.id.btnReceive)
        layoutQr = findViewById(R.id.layoutQr)
        ivQrCode = findViewById(R.id.ivQrCode)
        tvToken = findViewById(R.id.tvToken)

        btnSend.setOnClickListener {
            checkPermissionsAndRun { startAdvertising() }
        }

        btnReceive.setOnClickListener {
            checkPermissionsAndRun { startDiscoveryProcess() }
        }
    }

    private fun checkPermissionsAndRun(action: () -> Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val notGranted = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (notGranted.isNotEmpty()) {
            pendingAction = action
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            action()
        }
    }

    private fun startAdvertising() {
        stopAll()
        currentMode = 1
        pairingToken = UUID.randomUUID().toString().substring(0, 6).uppercase()
        
        layoutQr.visibility = View.VISIBLE
        tvToken.text = "Pairing Token: $pairingToken"
        generateQrCode(pairingToken)

        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(pairingToken, serviceId, connectionLifecycleCallback, options)
            .addOnSuccessListener { setStatus("Advertising... Scanning device needed.") }
            .addOnFailureListener { setStatus("Advertising failed: ${it.message}") }
    }

    private fun startDiscoveryProcess() {
        stopAll()
        currentMode = 2
        layoutQr.visibility = View.GONE
        
        launchQRCodeScanner { scanResult ->
            val result = scanResult?.trim()
            if (result.isNullOrBlank()) {
                setStatus("Scan cancelled")
            } else {
                targetToken = result
                setStatus("Token received: $targetToken. Searching...")
                startDiscovery(targetToken)
            }
        }
    }

    private fun startDiscovery(token: String) {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { setStatus("Searching for sender...") }
            .addOnFailureListener { setStatus("Discovery failed: ${it.message}") }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.endpointName == targetToken) {
                setStatus("Sender found! Connecting...")
                connectionsClient.requestConnection(Build.MODEL, endpointId, connectionLifecycleCallback)
            }
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            setStatus("Connecting with ${info.endpointName}")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                setStatus("Connected!")
                if (currentMode == 1) { // Sender
                    sendConfigs(endpointId)
                }
            } else {
                setStatus("Connection failed")
                stopAll()
            }
        }

        override fun onDisconnected(endpointId: String) {
            setStatus("Disconnected")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                setStatus("Data received. Processing...")
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val decompressed = GzipUtil.decompress(bytes)
                        val json = String(decompressed, Charsets.UTF_8)
                        val dto = Gson().fromJson(json, NearbyPackageDto::class.java)

                        dto.subscriptions?.forEach { sub ->
                            MmkvManager.encodeSubscription(sub.guid, sub.subscription)
                        }
                        dto.profiles?.forEach { profile ->
                            MmkvManager.encodeServerConfig(Utils.getUuid(), profile)
                        }
                        dto.rulesets?.let {
                            MmkvManager.encodeRoutingRulesets(it.toMutableList())
                        }
                        dto.settings?.forEach { (k, v) ->
                            MmkvManager.encodeSettings(k, v)
                        }
                        dto.mimicryPresets?.let {
                            MmkvManager.encodeMimicryPresets(it)
                        }

                        withContext(Dispatchers.Main) {
                            setStatus("Import complete!")
                            Toast.makeText(this@NearbySharingActivity, "Sync Successful", Toast.LENGTH_LONG).show()
                            stopAll()
                            finish()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            setStatus("Import error: ${e.message}")
                            stopAll()
                        }
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendConfigs(endpointId: String) {
        setStatus("Packaging configurations...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val profiles = MmkvManager.decodeAllServerList().mapNotNull { MmkvManager.decodeServerConfig(it) }
                val subscriptions = MmkvManager.decodeSubscriptions()
                val rulesets = MmkvManager.decodeRoutingRulesets() ?: emptyList()
                val mimicryPresets = MmkvManager.decodeMimicryPresets()
                
                val settings = mutableMapOf<String, String>()
                
                val dto = NearbyPackageDto(profiles, subscriptions, rulesets, settings, mimicryPresets)
                
                val json = Gson().toJson(dto)
                val bytes = json.toByteArray(Charsets.UTF_8)
                val compressed = GzipUtil.compress(bytes)

                withContext(Dispatchers.Main) { setStatus("Sending packet...") }
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(compressed))
                
                launch {
                    kotlinx.coroutines.delay(3000)
                    withContext(Dispatchers.Main) {
                        setStatus("Transfer finished!")
                        stopAll()
                        finish()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setStatus("Send failed: ${e.message}")
                    stopAll()
                }
            }
        }
    }

    private fun generateQrCode(text: String) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setStatus(msg: String) {
        tvStatus.text = msg
        Log.d("NearbyShare", msg)
    }

    private fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }

    override fun onStop() {
        super.onStop()
        stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAll()
    }
}

