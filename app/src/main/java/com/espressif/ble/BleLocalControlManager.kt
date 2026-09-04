// Copyright 2026 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.espressif.AppConstants
import com.espressif.EspApplication
import com.espressif.rainmaker.BuildConfig
import com.espressif.cloudapi.ApiResponseListener
import com.espressif.provisioning.DeviceConnectionEvent
import com.espressif.provisioning.ESPConstants
import com.espressif.provisioning.ESPDevice
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.provisioning.listeners.BleScanListener
import com.espressif.provisioning.listeners.ResponseListener
import com.espressif.ui.models.EspNode
import com.espressif.utils.EspNetworkMonitor
import com.espressif.utils.ParamTransportResolver
import com.google.gson.JsonObject
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized manager for BLE local control connections.
 *
 * Connection flow (lazy / on-demand):
 * 1. Single broad scan with "PROV_" prefix discovers all nearby ESP BLE devices
 * 2. Discovered devices are matched against nodes with ble_local_ctrl metadata
 *    and marked DISCOVERED (shown as "Reachable on BLE" — no GATT connection yet)
 * 3. Actual BLE connection + session init happens on demand when the user
 *    controls a param or opens the device detail screen
 */
class BleLocalControlManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "BleLocalCtrlMgr"
        private const val SCAN_TIMEOUT_MS = 10000L
        private const val SCAN_RETRY_DELAY_MS = 2000L
        private const val MAX_SCAN_RETRIES = 3
        private const val BLE_DEVICE_PREFIX = "PROV_"
        private const val BLE_OPERATION_TIMEOUT_MS = 5000L

        @Volatile
        private var instance: BleLocalControlManager? = null

        @JvmStatic
        fun getInstance(context: Context): BleLocalControlManager {
            return instance ?: synchronized(this) {
                instance ?: BleLocalControlManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    enum class ConnectionState {
        DISCONNECTED, DISCOVERED, CONNECTING, SESSION_INIT, CONNECTED
    }

    data class BleDeviceConnection(
        var espDevice: ESPDevice? = null,
        val bleInfo: EspNode.BleLocalCtrlInfo,
        var state: ConnectionState = ConnectionState.DISCONNECTED,
        val nodeId: String,
        var bluetoothDevice: BluetoothDevice? = null,
        var serviceUuid: String = ""
    )

    interface BleConnectionListener {
        fun onDeviceConnected(nodeId: String)
        fun onDeviceDisconnected(nodeId: String)
        fun onAllDevicesProcessed()
    }

    private val espApp = appContext as EspApplication
    private val provisionManager = ESPProvisionManager.getInstance(appContext)
    private val handler = Handler(Looper.getMainLooper())

    private val connectionMap = ConcurrentHashMap<String, BleDeviceConnection>()
    private var currentConnectingNodeId: String? = null
    private var isBleScanning = false
    private var scanRetryCount = 0

    private val listeners = mutableListOf<BleConnectionListener>()

    // Callback for on-demand connectDevice() / connectAndSendParams()
    private var connectCallback: ((Boolean) -> Unit)? = null

    // Tracks nodes that have getParamsWithTimestamp in progress to avoid concurrent BLE reads
    private val proxyReadInProgress = ConcurrentHashMap<String, AtomicBoolean>()

    /** Node ids whose advertisement was seen in the current scan window. */
    private val seenThisScan = mutableSetOf<String>()

    /**
     * Every BLE operation on a node shares one Security 2 session, whose AES-GCM counter
     * advances per message. Two operations in flight at once desynchronise it and the
     * next response fails to decrypt (AEADBadTagException). These queues keep exactly one
     * operation outstanding per node. Only touched on the main thread.
     */
    private val bleOpQueue = HashMap<String, ArrayDeque<BleOp>>()
    private val bleOpRunning = HashSet<String>()

    private sealed class BleOp {

        abstract fun fail(nodeId: String)

        class SetParams(val body: JsonObject, val listener: ApiResponseListener) : BleOp() {
            override fun fail(nodeId: String) {
                listener.onNetworkFailure(Exception("BLE operation cancelled for $nodeId"))
            }
        }

        class QueryParams(val onResult: (org.json.JSONObject?) -> Unit) : BleOp() {
            override fun fail(nodeId: String) = onResult(null)
        }

        class GetParamsWithTimestamp(val onResult: (org.json.JSONObject?) -> Unit) : BleOp() {
            override fun fail(nodeId: String) = onResult(null)
        }
    }

    /**
     * Losing or regaining internet changes which transport a node should use, and while
     * offline no cloud refresh arrives to trigger a re-evaluation. Reacting directly keeps
     * BLE reachability correct without depending on app state or any screen being open.
     */
    private val connectivityListener = EspNetworkMonitor.Listener { connected ->
        handler.post {
            Log.d(TAG, "Connectivity changed (internet=$connected), re-evaluating BLE state")
            reapplyBleStatusToConnectedNodes()
            scanForDevices()
            notifyAllDevicesProcessed()
        }
    }

    init {
        EspNetworkMonitor.addListener(connectivityListener)
    }

    // --- Public API ---

    fun addListener(listener: BleConnectionListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: BleConnectionListener) {
        listeners.remove(listener)
    }

    fun isConnected(nodeId: String): Boolean {
        return connectionMap[nodeId]?.state == ConnectionState.CONNECTED
    }

    fun isDiscovered(nodeId: String): Boolean {
        return connectionMap[nodeId]?.state == ConnectionState.DISCOVERED
    }

    fun getEspDevice(nodeId: String): ESPDevice? {
        val conn = connectionMap[nodeId]
        return if (conn?.state == ConnectionState.CONNECTED) conn.espDevice else null
    }

    fun getDeviceCapabilities(nodeId: String): ArrayList<String>? {
        return getEspDevice(nodeId)?.deviceCapabilities
    }

    /**
     * Re-apply BLE statuses on all tracked nodes after a cloud data refresh
     * which replaces EspNode objects in nodeMap and resets their status.
     */
    fun reapplyBleStatusToConnectedNodes() {
        for ((nodeId, conn) in connectionMap) {
            when (conn.state) {
                ConnectionState.CONNECTED -> applyBleStatus(nodeId, AppConstants.NODE_STATUS_BLE_LOCAL)
                ConnectionState.DISCOVERED -> applyBleStatus(nodeId, AppConstants.NODE_STATUS_BLE_DISCOVERABLE)
                else -> {}
            }
        }
    }

    /**
     * Scan for all nearby BLE devices, match with nodes that have
     * ble_local_ctrl metadata, and mark them as DISCOVERED.
     * No GATT connections are made here.
     */
    fun scanForDevices() {
        if (isBleScanning) {
            Log.d(TAG, "Already scanning, skipping")
            return
        }

        if (!hasBlePermissions()) {
            Log.d(TAG, "BLE permissions not granted")
            return
        }

        val bleDevices = collectBleDevices()
        if (bleDevices.isEmpty()) {
            Log.d(TAG, "No BLE local control devices found in node metadata")
            return
        }

        for ((nodeId, bleInfo) in bleDevices) {
            val existing = connectionMap[nodeId]
            if (existing == null || existing.state == ConnectionState.DISCONNECTED) {
                connectionMap[nodeId] = BleDeviceConnection(
                    bleInfo = bleInfo,
                    nodeId = nodeId
                )
            }
        }

        Log.d(TAG, "Connection map before scan: " +
                connectionMap.values.joinToString { "${'$'}{it.bleInfo.name}=${'$'}{it.state}" })

        // DISCOVERED entries are re-scanned as well: without that, a node that was seen
        // once and has since powered off keeps claiming BLE reachability until relaunch.
        val pendingNodes = connectionMap.values.filter {
            it.state == ConnectionState.DISCONNECTED || it.state == ConnectionState.DISCOVERED
        }
        if (pendingNodes.isEmpty()) {
            Log.d(TAG, "All BLE devices already connected")
            notifyAllDevicesProcessed()
            return
        }

        Log.d(TAG, "Starting BLE scan for ${pendingNodes.size} devices")
        startBroadScan()
    }

    /**
     * Connect to a single discovered device on demand.
     * Call this when the user opens a device detail screen or needs BLE access.
     */
    fun connectDevice(nodeId: String, callback: (Boolean) -> Unit) {
        val conn = connectionMap[nodeId]
        if (conn == null || conn.bluetoothDevice == null) {
            Log.e(TAG, "connectDevice: no discovered device for node $nodeId")
            callback(false)
            return
        }

        if (conn.state == ConnectionState.CONNECTED) {
            Log.d(TAG, "connectDevice: already connected for $nodeId")
            callback(true)
            return
        }

        if (conn.state == ConnectionState.CONNECTING || conn.state == ConnectionState.SESSION_INIT) {
            Log.d(TAG, "connectDevice: connection already in progress for $nodeId")
            callback(false)
            return
        }

        if (currentConnectingNodeId != null) {
            Log.d(TAG, "connectDevice: another device is connecting ($currentConnectingNodeId), queuing")
            callback(false)
            return
        }

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }

        currentConnectingNodeId = nodeId
        connectCallback = callback
        connectToBleDevice(conn)
    }

    /**
     * Connect (if needed) and send params in one call.
     * If already connected, sends immediately. If discovered, connects first.
     */
    fun connectAndSendParams(nodeId: String, body: JsonObject, listener: ApiResponseListener) {

        // Priority 2: BLE local control
        if (isConnected(nodeId)) {
            sendParams(nodeId, body, listener)
            return
        }

        if (isDiscovered(nodeId)) {
            connectDevice(nodeId) { success ->
                if (success) {
                    sendParams(nodeId, body, listener)
                } else {
                    listener.onNetworkFailure(Exception("BLE connect failed for $nodeId"))
                }
            }
            return
        }

        listener.onNetworkFailure(Exception("BLE device not available for $nodeId"))
    }

    fun disconnectAll() {
        Log.d(TAG, "Disconnecting all BLE devices")
        for (nodeId in connectionMap.keys) {
            failQueuedBleOps(nodeId)
        }
        stopBleScan()
        currentConnectingNodeId = null
        connectCallback = null

        for ((nodeId, conn) in connectionMap) {
            if (conn.state == ConnectionState.CONNECTED) {
                try {
                    conn.espDevice?.disconnectDevice()
                } catch (e: Exception) {
                    Log.e(TAG, "Error disconnecting device $nodeId: ${e.message}")
                }
                notifyDeviceDisconnected(nodeId)
            }
            val node = espApp.nodeMap[nodeId]
            if (node != null) {
                node.nodeStatus = AppConstants.NODE_STATUS_OFFLINE
            }
        }
        connectionMap.clear()

        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    fun disconnectDevice(nodeId: String) {
        failQueuedBleOps(nodeId)
        val conn = connectionMap[nodeId] ?: return
        if (conn.state == ConnectionState.CONNECTED) {
            try {
                conn.espDevice?.disconnectDevice()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting device $nodeId: ${e.message}")
            }
            val node = espApp.nodeMap[nodeId]
            if (node != null) {
                node.nodeStatus = AppConstants.NODE_STATUS_OFFLINE
            }
            notifyDeviceDisconnected(nodeId)
        }
        conn.state = ConnectionState.DISCONNECTED
        conn.espDevice = null
        connectionMap.remove(nodeId)
    }

    fun sendParams(nodeId: String, body: JsonObject, listener: ApiResponseListener) {
        enqueueBleOp(nodeId, BleOp.SetParams(body, listener))
    }

    private fun performSendParams(nodeId: String, body: JsonObject, listener: ApiResponseListener) {
        val espDevice = getEspDevice(nodeId)
        if (espDevice == null) {
            listener.onNetworkFailure(Exception("BLE not connected for node $nodeId"))
            return
        }

        val jsonStr = body.toString()
        val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Sending params via BLE for $nodeId: $jsonStr")

        val callbackFired = AtomicBoolean(false)

        val timeoutRunnable = Runnable {
            if (callbackFired.compareAndSet(false, true)) {
                Log.e(TAG, "BLE set_params timed out for $nodeId, marking disconnected")
                handleBleOperationTimeout(nodeId)
                listener.onNetworkFailure(Exception("BLE operation timed out for $nodeId"))
            }
        }
        handler.postDelayed(timeoutRunnable, BLE_OPERATION_TIMEOUT_MS)

        espDevice.sendDataToCustomEndPoint(
            AppConstants.HANDLER_SET_PARAMS,
            jsonBytes,
            object : ResponseListener {
                override fun onSuccess(returnData: ByteArray?) {
                    if (callbackFired.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutRunnable)
                        Log.d(TAG, "BLE set_params success for $nodeId")
                        handler.post { listener.onSuccess(null) }
                    }
                }

                override fun onFailure(e: Exception) {
                    if (callbackFired.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutRunnable)
                        Log.e(TAG, "BLE set_params failed for $nodeId: ${e.message}")
                        handleBleOperationTimeout(nodeId)
                        handler.post { listener.onResponseFailure(e) }
                    }
                }
            }
        )
    }

    fun queryParams(
        nodeId: String,
        onResult: (org.json.JSONObject?) -> Unit
    ) {
        enqueueBleOp(nodeId, BleOp.QueryParams(onResult))
    }

    // The queue guarantees exclusivity, so no proxy-read check is needed here any more:
    // a read raised while a proxy read runs now waits its turn instead of being dropped.
    private fun performQueryParams(
        nodeId: String,
        onResult: (org.json.JSONObject?) -> Unit
    ) {
        val espDevice = getEspDevice(nodeId)
        if (espDevice == null) {
            Log.e(TAG, "Cannot query params: BLE not connected for node $nodeId")
            onResult(null)
            return
        }

        Log.d(TAG, "Querying params via BLE for $nodeId")
        val dataBuffer = ArrayList<Byte>()
        val callbackFired = AtomicBoolean(false)

        val wrappedResult: (org.json.JSONObject?) -> Unit = { json ->
            if (callbackFired.compareAndSet(false, true)) {
                onResult(json)
            }
        }

        val timeoutRunnable = Runnable {
            if (callbackFired.compareAndSet(false, true)) {
                Log.e(TAG, "BLE queryParams timed out for $nodeId, marking disconnected")
                handleBleOperationTimeout(nodeId)
                onResult(null)
            }
        }
        handler.postDelayed(timeoutRunnable, BLE_OPERATION_TIMEOUT_MS)

        getParamsChunk(espDevice, nodeId, 0, dataBuffer, null) { json ->
            handler.removeCallbacks(timeoutRunnable)
            wrappedResult(json)
        }
    }

    // --- Internal: node status ---

    /**
     * Applies a BLE status to a node, but only while BLE is the transport that would
     * actually be used.
     *
     * A node reachable over WLAN or the cloud is served by those instead, so showing it
     * as "Reachable on BLE" would be wrong, and NODE_STATUS_BLE_DISCOVERABLE would also
     * leave its controls inert in ParamAdapter. If such a node still carries a BLE status
     * from earlier, it is put back on the status its own connectivity implies.
     */
    private fun applyBleStatus(nodeId: String, bleStatus: Int) {
        val node = espApp.nodeMap[nodeId] ?: return

        if (ParamTransportResolver.isHigherPriorityThanBleAvailable(espApp, nodeId)) {
            Log.d(TAG, "Not applying BLE status for $nodeId, served by WLAN/cloud instead")
            clearBleStatus(nodeId)
            return
        }

        if (node.nodeStatus != bleStatus) {
            Log.d(TAG, "Applying BLE status $bleStatus for node $nodeId")
            node.nodeStatus = bleStatus
        }
    }

    /** Puts a node back on the status its own connectivity implies. */
    private fun clearBleStatus(nodeId: String) {
        val node = espApp.nodeMap[nodeId] ?: return
        if (node.nodeStatus != AppConstants.NODE_STATUS_BLE_LOCAL
            && node.nodeStatus != AppConstants.NODE_STATUS_BLE_DISCOVERABLE
        ) {
            return
        }
        node.nodeStatus = when {
            espApp.localDeviceMap.containsKey(nodeId) -> AppConstants.NODE_STATUS_LOCAL
            node.isOnline -> AppConstants.NODE_STATUS_ONLINE
            else -> AppConstants.NODE_STATUS_OFFLINE
        }
        Log.d(TAG, "Cleared BLE status for $nodeId -> ${node.nodeStatus}")
    }

    // --- Internal: BLE operation queue ---

    /**
     * Queues an operation and starts it when the node has no other operation running.
     * Safe to call from any thread; the queue itself is main-thread only.
     */
    private fun enqueueBleOp(nodeId: String, op: BleOp) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyEnqueue(nodeId, op)
        } else {
            handler.post { applyEnqueue(nodeId, op) }
        }
    }

    private fun applyEnqueue(nodeId: String, op: BleOp) {
        val queue = bleOpQueue.getOrPut(nodeId) { ArrayDeque() }
        val last = queue.lastOrNull()

        if (op is BleOp.SetParams && last is BleOp.SetParams) {
            // A slider produces updates faster than BLE can carry them. Replace the value
            // still waiting with the newer one and chain both callbacks, so intermediate
            // values are dropped but no caller is left without a response.
            queue.removeLast()
            queue.addLast(BleOp.SetParams(op.body, chainListeners(last.listener, op.listener)))
        } else {
            queue.addLast(op)
        }

        pumpBleOps(nodeId)
    }

    private fun pumpBleOps(nodeId: String) {
        if (bleOpRunning.contains(nodeId)) {
            return
        }
        val queue = bleOpQueue[nodeId]
        if (queue == null || queue.isEmpty()) {
            bleOpQueue.remove(nodeId)
            return
        }
        val op = queue.removeFirst()
        if (queue.isEmpty()) {
            bleOpQueue.remove(nodeId)
        }
        bleOpRunning.add(nodeId)
        executeBleOp(nodeId, op)
    }

    /** Always posted, so a completion firing inline cannot recurse into the next operation. */
    private fun finishBleOp(nodeId: String) {
        handler.post {
            bleOpRunning.remove(nodeId)
            pumpBleOps(nodeId)
        }
    }

    /** Drains everything still queued for a node, e.g. after a disconnect or timeout. */
    private fun failQueuedBleOps(nodeId: String) {
        val drain = {
            val queue = bleOpQueue.remove(nodeId)
            if (queue != null) {
                while (queue.isNotEmpty()) {
                    queue.removeFirst().fail(nodeId)
                }
            }
            bleOpRunning.remove(nodeId)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            drain()
        } else {
            handler.post { drain() }
        }
    }

    private fun executeBleOp(nodeId: String, op: BleOp) {
        when (op) {
            is BleOp.SetParams -> performSendParams(nodeId, op.body, object : ApiResponseListener {
                override fun onSuccess(data: Bundle?) {
                    op.listener.onSuccess(data)
                    finishBleOp(nodeId)
                }

                override fun onResponseFailure(exception: Exception) {
                    op.listener.onResponseFailure(exception)
                    finishBleOp(nodeId)
                }

                override fun onNetworkFailure(exception: Exception) {
                    op.listener.onNetworkFailure(exception)
                    finishBleOp(nodeId)
                }
            })

            is BleOp.QueryParams -> performQueryParams(nodeId) { json ->
                op.onResult(json)
                finishBleOp(nodeId)
            }

            is BleOp.GetParamsWithTimestamp -> {
                val busy = proxyReadInProgress.getOrPut(nodeId) { AtomicBoolean(false) }
                busy.set(true)
                performGetParamsWithTimestamp(nodeId) { json ->
                    busy.set(false)
                    op.onResult(json)
                    finishBleOp(nodeId)
                }
            }
        }
    }

    private fun chainListeners(first: ApiResponseListener, second: ApiResponseListener) =
        object : ApiResponseListener {
            override fun onSuccess(data: Bundle?) {
                first.onSuccess(data)
                second.onSuccess(data)
            }

            override fun onResponseFailure(exception: Exception) {
                first.onResponseFailure(exception)
                second.onResponseFailure(exception)
            }

            override fun onNetworkFailure(exception: Exception) {
                first.onNetworkFailure(exception)
                second.onNetworkFailure(exception)
            }
        }

    // --- Internal: scan phase ---

    private fun collectBleDevices(): Map<String, EspNode.BleLocalCtrlInfo> {
        val result = mutableMapOf<String, EspNode.BleLocalCtrlInfo>()
        for ((nodeId, node) in espApp.nodeMap) {
            val bleInfo = node.getBleLocalCtrlInfo()
            if (bleInfo != null) {
                result[nodeId] = bleInfo
            }
        }
        return result
    }

    private fun startBroadScan() {
        scanRetryCount = 0
        attemptBleScan()
    }

    private fun attemptBleScan() {
        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter is null - BLE not supported on this device")
            onScanPhaseComplete()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled. Please enable Bluetooth to scan for BLE devices.")
            onScanPhaseComplete()
            return
        }

        isBleScanning = true
        seenThisScan.clear()
        Log.d(TAG, "Starting broad BLE scan with prefix '$BLE_DEVICE_PREFIX' (attempt ${scanRetryCount + 1})")

        provisionManager.searchBleEspDevices(BLE_DEVICE_PREFIX, bleScanListener)

        handler.postDelayed({
            if (isBleScanning) {
                Log.d(TAG, "Broad scan timeout reached, stopping scan")
                stopBleScan()
                onScanPhaseComplete()
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopBleScan() {
        if (isBleScanning) {
            isBleScanning = false
            try {
                provisionManager.stopBleScan()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping BLE scan: ${e.message}")
            }
        }
    }

    /**
     * After scan completes, mark matched devices as DISCOVERED and set
     * their node status to BLE_DISCOVERABLE. No GATT connection is made.
     */
    private fun onScanPhaseComplete() {

        val matched = connectionMap.values.filter {
            it.state == ConnectionState.DISCONNECTED && it.bluetoothDevice != null
        }

        if (matched.isEmpty()) {
            Log.d(TAG, "No BLE devices discovered during scan")
        } else {
            for (conn in matched) {
                conn.state = ConnectionState.DISCOVERED
                applyBleStatus(conn.nodeId, AppConstants.NODE_STATUS_BLE_DISCOVERABLE)
                Log.d(TAG, "Marked DISCOVERED: ${conn.bleInfo.name} (node: ${conn.nodeId})")
            }
            Log.d(TAG, "Scan complete. ${matched.size} devices marked as discoverable")
        }

        // A node that was DISCOVERED but did not advertise in this window is gone. Without
        // this it keeps claiming BLE reachability for the life of the process.
        val missing = connectionMap.values.filter {
            it.state == ConnectionState.DISCOVERED && !seenThisScan.contains(it.nodeId)
        }
        for (conn in missing) {
            conn.state = ConnectionState.DISCONNECTED
            conn.bluetoothDevice = null
            clearBleStatus(conn.nodeId)
            Log.d(TAG, "No longer advertising, demoted: ${conn.bleInfo.name} (node: ${conn.nodeId})")
        }

        notifyAllDevicesProcessed()
    }

    // --- Internal: on-demand connect ---

    private fun connectToBleDevice(conn: BleDeviceConnection) {
        val bluetoothDevice = conn.bluetoothDevice ?: return

        Log.d(TAG, "Connecting to BLE device: ${bluetoothDevice.name} for node ${conn.nodeId}")
        conn.state = ConnectionState.CONNECTING

        // initSession() overrides this from the device's own sec_ver, so the type passed
        // here is only a fallback for firmware that does not report one.
        val espDevice = ESPDevice(appContext, ESPConstants.TransportType.TRANSPORT_BLE, ESPConstants.SecurityType.SECURITY_1)
        espDevice.proofOfPossession = conn.bleInfo.pop
        // Security 2 builds an SRP session from this; it fails with "user identity 'I'
        // must not be null or empty" when unset. Harmless for Security 0/1.
        espDevice.userName = BuildConfig.LOCAL_CONTROL_SECURITY_2_USERNAME
        espDevice.bluetoothDevice = bluetoothDevice
        espDevice.primaryServiceUuid = conn.serviceUuid
        conn.espDevice = espDevice

        espDevice.connectBLEDevice(bluetoothDevice, conn.serviceUuid)
    }

    private fun initBleSession(nodeId: String) {
        val conn = connectionMap[nodeId] ?: return
        val espDevice = conn.espDevice ?: return

        conn.state = ConnectionState.SESSION_INIT
        Log.d(TAG, "Initializing BLE session for $nodeId")

        espDevice.initSession(object : ResponseListener {
            override fun onSuccess(returnData: ByteArray?) {
                Log.d(TAG, "BLE session success for $nodeId")
                handler.post {
                    conn.state = ConnectionState.CONNECTED

                    applyBleStatus(nodeId, AppConstants.NODE_STATUS_BLE_LOCAL)

                    notifyDeviceConnected(nodeId)

                    val cb = connectCallback
                    connectCallback = null
                    currentConnectingNodeId = null
                    cb?.invoke(true)
                }
            }

            override fun onFailure(e: Exception) {
                Log.e(TAG, "BLE session failed for $nodeId: ${e.message}")
                handler.post {
                    conn.state = ConnectionState.DISCOVERED
                    conn.espDevice = null

                    val cb = connectCallback
                    connectCallback = null
                    currentConnectingNodeId = null
                    cb?.invoke(false)
                }
            }
        })
    }

    // --- BLE scan listener ---

    private val bleScanListener = object : BleScanListener {
        override fun scanStartFailed() {
            isBleScanning = false
            scanRetryCount++
            if (scanRetryCount < MAX_SCAN_RETRIES) {
                Log.w(TAG, "BLE scan start failed, retrying in ${SCAN_RETRY_DELAY_MS}ms (attempt $scanRetryCount/$MAX_SCAN_RETRIES)")
                handler.postDelayed({ attemptBleScan() }, SCAN_RETRY_DELAY_MS)
            } else {
                Log.e(TAG, "BLE scan start failed after $MAX_SCAN_RETRIES attempts")
                handler.post { onScanPhaseComplete() }
            }
        }

        override fun onPeripheralFound(device: BluetoothDevice, scanResult: ScanResult) {
            val deviceName = scanResult.scanRecord?.deviceName ?: return

            for (conn in connectionMap.values) {
                if ((conn.state == ConnectionState.DISCONNECTED || conn.state == ConnectionState.DISCOVERED)
                    && deviceName == conn.bleInfo.name
                ) {
                    seenThisScan.add(conn.nodeId)
                    val serviceUuid = if (scanResult.scanRecord?.serviceUuids?.isNotEmpty() == true) {
                        scanResult.scanRecord!!.serviceUuids!![0].toString()
                    } else {
                        ""
                    }
                    conn.bluetoothDevice = device
                    conn.serviceUuid = serviceUuid
                    Log.d(TAG, "Scan matched: $deviceName -> node ${conn.nodeId}")
                    break
                }
            }
        }

        override fun scanCompleted() {
            Log.d(TAG, "BLE scan completed")
            isBleScanning = false
            handler.post { onScanPhaseComplete() }
        }

        override fun onFailure(e: Exception) {
            Log.e(TAG, "BLE scan failure: ${e.message}")
            isBleScanning = false
            handler.post { onScanPhaseComplete() }
        }
    }

    // --- EventBus: DeviceConnectionEvent ---

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(event: DeviceConnectionEvent) {

        // A disconnect can arrive long after the connect finished, when
        // currentConnectingNodeId is already cleared, so it is resolved separately.
        if (event.eventType == ESPConstants.EVENT_DEVICE_DISCONNECTED) {
            handleGattDisconnected()
            return
        }

        val nodeId = currentConnectingNodeId ?: return

        when (event.eventType) {
            ESPConstants.EVENT_DEVICE_CONNECTED -> {
                Log.d(TAG, "BLE device connected for node $nodeId - initializing session")
                initBleSession(nodeId)
            }
            ESPConstants.EVENT_DEVICE_CONNECTION_FAILED -> {
                Log.e(TAG, "BLE device connection failed for node $nodeId")
                val conn = connectionMap[nodeId]
                conn?.state = ConnectionState.DISCOVERED
                conn?.espDevice = null
                val cb = connectCallback
                connectCallback = null
                currentConnectingNodeId = null
                cb?.invoke(false)
            }
        }
    }

    /**
     * A GATT link dropped. DeviceConnectionEvent carries no device identity, so the node
     * is worked out by asking the system which devices are still GATT-connected: any of
     * our CONNECTED sessions missing from that list is the one that went away.
     */
    private fun handleGattDisconnected() {

        val connectingNodeId = currentConnectingNodeId
        if (connectingNodeId != null) {
            // A connect attempt owns this event. The device may still be advertising, so
            // leave it discovered rather than declaring it gone.
            Log.d(TAG, "BLE disconnected during connect for node $connectingNodeId")
            val conn = connectionMap[connectingNodeId]
            conn?.state = ConnectionState.DISCOVERED
            conn?.espDevice = null
            val cb = connectCallback
            connectCallback = null
            currentConnectingNodeId = null
            cb?.invoke(false)
            return
        }

        val stillConnected = gattConnectedAddresses()
        val dropped = connectionMap.values.filter { conn ->
            conn.state == ConnectionState.CONNECTED
                    && conn.bluetoothDevice?.address?.let { !stillConnected.contains(it) } ?: true
        }

        if (dropped.isEmpty()) {
            Log.d(TAG, "GATT disconnect did not match any tracked BLE session")
            return
        }

        for (conn in dropped) {
            Log.d(TAG, "BLE session lost for node ${conn.nodeId} (${conn.bleInfo.name})")
            markBleUnreachable(conn.nodeId)
        }
    }

    /**
     * The peripheral is gone - powered off or out of range. Drop straight to DISCONNECTED
     * and clear the BLE status so the node reads as offline. Demoting to DISCOVERED would
     * keep claiming "Reachable on BLE" for a device that is switched off; a later scan
     * promotes it again if it comes back.
     */
    private fun markBleUnreachable(nodeId: String) {
        failQueuedBleOps(nodeId)
        val conn = connectionMap[nodeId] ?: return
        try {
            conn.espDevice?.disconnectDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting lost device $nodeId: ${e.message}")
        }
        conn.espDevice = null
        conn.bluetoothDevice = null
        conn.state = ConnectionState.DISCONNECTED
        clearBleStatus(nodeId)
        notifyDeviceDisconnected(nodeId)
    }

    private fun gattConnectedAddresses(): Set<String> {
        return try {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.getConnectedDevices(BluetoothProfile.GATT)
                ?.mapNotNull { it.address }
                ?.toSet()
                ?: emptySet()
        } catch (e: Exception) {
            // Missing BLUETOOTH_CONNECT leaves us unable to tell; treat as none connected
            // so a real disconnect is not ignored.
            Log.e(TAG, "Failed to read GATT connected devices: ${e.message}")
            emptySet()
        }
    }

    // --- Chunked get_params via protobuf ---

    private fun getParamsChunk(
        espDevice: ESPDevice,
        nodeId: String,
        offset: Int,
        dataBuffer: ArrayList<Byte>,
        totalLen: Int?,
        onResult: (org.json.JSONObject?) -> Unit
    ) {
        val cmdGetData = rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.CmdGetData.newBuilder()
            .setDataType(rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlDataType.TypeParams)
            .setOffset(offset)
            .setHasTimestamp(false)
            .build()

        val payload = rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlPayload.newBuilder()
            .setMsg(rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlMsgType.TypeCmdGetData)
            .setCmdGetData(cmdGetData)
            .build()

        espDevice.sendDataToCustomEndPoint(
            AppConstants.HANDLER_GET_PARAMS,
            payload.toByteArray(),
            object : ResponseListener {
                override fun onSuccess(returnData: ByteArray?) {
                    if (returnData == null || returnData.isEmpty()) {
                        Log.w(TAG, "get_params returned empty data for $nodeId")
                        handler.post { onResult(null) }
                        return
                    }

                    try {
                        val response = rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlPayload.parseFrom(returnData)
                        if (response.msg != rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlMsgType.TypeRespGetData) {
                            Log.e(TAG, "Unexpected message type for $nodeId: ${response.msg}")
                            handler.post { onResult(null) }
                            return
                        }

                        val respGetData = response.respGetData
                        if (respGetData.status != rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlStatus.Success) {
                            Log.e(TAG, "Device returned error for $nodeId: ${respGetData.status}")
                            handler.post { onResult(null) }
                            return
                        }

                        val buf = respGetData.buf
                        val respOffset = buf.offset
                        val payloadBytes = buf.payload.toByteArray()
                        val respTotalLen = buf.totalLen

                        if (respOffset != offset) {
                            Log.e(TAG, "Offset mismatch for $nodeId: expected $offset, got $respOffset")
                            handler.post { onResult(null) }
                            return
                        }

                        val currentTotalLen = totalLen ?: respTotalLen

                        for (b in payloadBytes) {
                            dataBuffer.add(b)
                        }
                        val newOffset = offset + payloadBytes.size

                        Log.d(TAG, "Params chunk for $nodeId: offset=$respOffset, len=${payloadBytes.size}, progress=$newOffset/$currentTotalLen")

                        if (newOffset >= currentTotalLen) {
                            val completeData = ByteArray(dataBuffer.size)
                            for (i in dataBuffer.indices) {
                                completeData[i] = dataBuffer[i]
                            }
                            val jsonStr = String(completeData, Charsets.UTF_8)
                            Log.d(TAG, "Complete params JSON for $nodeId: $jsonStr")

                            try {
                                val jsonObject = org.json.JSONObject(jsonStr)
                                handler.post { onResult(jsonObject) }
                            } catch (e: org.json.JSONException) {
                                Log.e(TAG, "Failed to parse params JSON for $nodeId: ${e.message}")
                                handler.post { onResult(null) }
                            }
                        } else {
                            getParamsChunk(espDevice, nodeId, newOffset, dataBuffer, currentTotalLen, onResult)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse get_params response for $nodeId: ${e.message}")
                        handler.post { onResult(null) }
                    }
                }

                override fun onFailure(e: Exception) {
                    Log.e(TAG, "get_params chunk failed for $nodeId: ${e.message}")
                    handler.post { onResult(null) }
                }
            }
        )
    }

    // --- Chunked get_params with timestamp (for proxy reporting) ---

    fun isProxyReadInProgress(nodeId: String): Boolean {
        return proxyReadInProgress[nodeId]?.get() == true
    }

    fun getParamsWithTimestamp(nodeId: String, onResult: (org.json.JSONObject?) -> Unit) {
        enqueueBleOp(nodeId, BleOp.GetParamsWithTimestamp(onResult))
    }

    // executeBleOp owns the proxyReadInProgress flag around this call.
    private fun performGetParamsWithTimestamp(nodeId: String, onResult: (org.json.JSONObject?) -> Unit) {
        val espDevice = getEspDevice(nodeId)
        if (espDevice == null) {
            Log.e(TAG, "Cannot get params with timestamp: BLE not connected for node $nodeId")
            onResult(null)
            return
        }

        val timestamp = System.currentTimeMillis() / 1000
        Log.d(TAG, "Getting params with timestamp=$timestamp for $nodeId")
        getParamsChunkWithTimestamp(espDevice, nodeId, 0, timestamp, ArrayList(), null, onResult)
    }

    private fun getParamsChunkWithTimestamp(
        espDevice: ESPDevice,
        nodeId: String,
        offset: Int,
        timestamp: Long?,
        dataBuffer: ArrayList<Byte>,
        totalLen: Int?,
        onResult: (org.json.JSONObject?) -> Unit
    ) {
        val cmdBuilder = rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.CmdGetData.newBuilder()
            .setDataType(rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlDataType.TypeParams)
            .setOffset(offset)

        if (timestamp != null) {
            cmdBuilder.setTimestamp(timestamp)
            cmdBuilder.setHasTimestamp(true)
        } else {
            cmdBuilder.setTimestamp(0)
            cmdBuilder.setHasTimestamp(false)
        }

        val payload = rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlPayload.newBuilder()
            .setMsg(rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlMsgType.TypeCmdGetData)
            .setCmdGetData(cmdBuilder.build())
            .build()

        espDevice.sendDataToCustomEndPoint(
            AppConstants.HANDLER_GET_PARAMS,
            payload.toByteArray(),
            object : ResponseListener {
                override fun onSuccess(returnData: ByteArray?) {
                    if (returnData == null || returnData.isEmpty()) {
                        Log.w(TAG, "get_params (timestamped) returned empty data for $nodeId")
                        handler.post { onResult(null) }
                        return
                    }

                    try {
                        val response = rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlPayload.parseFrom(returnData)
                        if (response.msg != rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlMsgType.TypeRespGetData) {
                            Log.e(TAG, "Unexpected message type (timestamped) for $nodeId: ${response.msg}")
                            handler.post { onResult(null) }
                            return
                        }

                        val respGetData = response.respGetData
                        if (respGetData.status != rmaker_prov_local_ctrl.EspRmakerProvLocalCtrl.RMakerLocalCtrlStatus.Success) {
                            Log.e(TAG, "Device returned error (timestamped) for $nodeId: ${respGetData.status}")
                            handler.post { onResult(null) }
                            return
                        }

                        val buf = respGetData.buf
                        val respOffset = buf.offset
                        val payloadBytes = buf.payload.toByteArray()
                        val respTotalLen = buf.totalLen

                        if (respOffset != offset) {
                            Log.e(TAG, "Offset mismatch (timestamped) for $nodeId: expected $offset, got $respOffset")
                            handler.post { onResult(null) }
                            return
                        }

                        val currentTotalLen = totalLen ?: respTotalLen

                        for (b in payloadBytes) {
                            dataBuffer.add(b)
                        }
                        val newOffset = offset + payloadBytes.size

                        Log.d(TAG, "Timestamped params chunk for $nodeId: offset=$respOffset, len=${payloadBytes.size}, progress=$newOffset/$currentTotalLen")

                        if (newOffset >= currentTotalLen) {
                            val completeData = ByteArray(dataBuffer.size)
                            for (i in dataBuffer.indices) {
                                completeData[i] = dataBuffer[i]
                            }
                            val jsonStr = String(completeData, Charsets.UTF_8)
                            Log.d(TAG, "Complete timestamped params JSON for $nodeId: $jsonStr")

                            try {
                                val jsonObject = org.json.JSONObject(jsonStr)
                                handler.post { onResult(jsonObject) }
                            } catch (e: org.json.JSONException) {
                                Log.e(TAG, "Failed to parse timestamped params JSON for $nodeId: ${e.message}")
                                handler.post { onResult(null) }
                            }
                        } else {
                            getParamsChunkWithTimestamp(espDevice, nodeId, newOffset, null, dataBuffer, currentTotalLen, onResult)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse timestamped get_params response for $nodeId: ${e.message}")
                        handler.post { onResult(null) }
                    }
                }

                override fun onFailure(e: Exception) {
                    Log.e(TAG, "get_params (timestamped) chunk failed for $nodeId: ${e.message}")
                    handler.post { onResult(null) }
                }
            }
        )
    }

    // --- Permissions ---

    // Context is enough for checkSelfPermission, so discovery does not need an Activity
    // and is not tied to any screen's lifecycle.
    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Handles a BLE operation timeout or failure by cleaning up the stale
     * connection state. Sets the connection back to DISCOVERED so a
     * reconnect can be attempted on the next user action.
     */
    private fun handleBleOperationTimeout(nodeId: String) {
        failQueuedBleOps(nodeId)
        val conn = connectionMap[nodeId] ?: return
        Log.w(TAG, "Cleaning up stale BLE connection for $nodeId")
        try {
            conn.espDevice?.disconnectDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting stale device $nodeId: ${e.message}")
        }
        conn.espDevice = null
        if (conn.bluetoothDevice != null) {
            conn.state = ConnectionState.DISCOVERED
            applyBleStatus(nodeId, AppConstants.NODE_STATUS_BLE_DISCOVERABLE)
        } else {
            conn.state = ConnectionState.DISCONNECTED
            connectionMap.remove(nodeId)
            val node = espApp.nodeMap[nodeId]
            if (node != null) {
                if (node.isOnline) {
                    node.nodeStatus = AppConstants.NODE_STATUS_ONLINE
                } else {
                    node.nodeStatus = AppConstants.NODE_STATUS_OFFLINE
                }
            }
        }
        notifyDeviceDisconnected(nodeId)
    }

    // --- Listener notifications ---

    private fun notifyDeviceConnected(nodeId: String) {
        for (listener in listeners) {
            listener.onDeviceConnected(nodeId)
        }
    }

    private fun notifyDeviceDisconnected(nodeId: String) {
        for (listener in listeners) {
            listener.onDeviceDisconnected(nodeId)
        }
    }

    private fun notifyAllDevicesProcessed() {
        for (listener in listeners) {
            listener.onAllDevicesProcessed()
        }
    }
}
