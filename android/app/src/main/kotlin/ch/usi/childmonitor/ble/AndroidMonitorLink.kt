package ch.usi.childmonitor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import ch.usi.childmonitor.domain.Channel
import ch.usi.childmonitor.domain.RawPayload
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@SuppressLint("MissingPermission")
class AndroidMonitorLink(
    context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : MonitorLink {

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val deviceList = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val diagnosticsState = MutableStateFlow(GattDiagnostics())
    private val payloadFlow = MutableSharedFlow<RawPayload>(
        extraBufferCapacity = PAYLOAD_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val connection: StateFlow<ConnectionState> = connectionState.asStateFlow()
    override val devices: StateFlow<List<ScannedDevice>> = deviceList.asStateFlow()
    override val diagnostics: StateFlow<GattDiagnostics> = diagnosticsState.asStateFlow()
    override val payloads: Flow<RawPayload> = payloadFlow.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var targetAddress: String? = null
    @Volatile private var userRequestedDisconnect = false
    @Volatile private var reconnectAttempts = 0
    private var scanning = false

    private val operations = ArrayDeque<GattOperation>()
    private var operationInFlight = false

    private val scanTimeout = Runnable {
        stopScan()
        log("Scan finished")
    }

    private val reconnectRunnable = Runnable {
        targetAddress?.let { openGatt(it) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addDevice(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach(::addDevice)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            fail("Scan failed (error $errorCode). On Android 11 and older, location services must be on.")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) {
                g.close()
                return
            }
            when {
                status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED ->
                    onLinkLost(g, status)
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected, discovering services")
                    if (!g.discoverServices()) log("discoverServices() was rejected")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed (status $status)")
                g.disconnect()
                return
            }
            onServicesReady(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Subscribe failed for ${descriptor.characteristic.uuid} (status $status)")
            }
            onOperationFinished()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(characteristic, value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            handleRead(characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            publish(characteristic, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            publish(characteristic, characteristic.value ?: ByteArray(0))
        }
    }

    override fun startScan() {
        val state = connectionState.value
        if (state is ConnectionState.Scanning || state is ConnectionState.Connecting ||
            state is ConnectionState.Connected || state is ConnectionState.Reconnecting
        ) {
            return
        }
        val bluetoothAdapter = adapter?.takeIf { it.isEnabled }
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            fail("Bluetooth is off or not available")
            return
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleContract.SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(BleContract.DEVICE_NAME).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        deviceList.value = emptyList()
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            fail("Bluetooth permission is missing")
            return
        }
        scanning = true
        connectionState.value = ConnectionState.Scanning
        log("Scan started")
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MILLIS)
    }

    override fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) {
            scanning = false
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: IllegalStateException) {
            } catch (_: SecurityException) {
            }
        }
        if (connectionState.value is ConnectionState.Scanning) {
            connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun connect(address: String) {
        stopScan()
        handler.removeCallbacks(reconnectRunnable)
        userRequestedDisconnect = false
        reconnectAttempts = 0
        targetAddress = address
        openGatt(address)
    }

    override fun disconnect() {
        userRequestedDisconnect = true
        handler.removeCallbacks(reconnectRunnable)
        stopScan()
        val current = gatt
        gatt = null
        clearOperations()
        if (current != null) {
            try {
                current.disconnect()
            } catch (_: SecurityException) {
                current.close()
            }
        }
        connectionState.value = ConnectionState.Disconnected
        log("Disconnected by user")
    }

    private fun openGatt(address: String) {
        val device: BluetoothDevice? = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            fail("Invalid device address")
            return
        }
        connectionState.value = if (reconnectAttempts > 0) {
            ConnectionState.Reconnecting(reconnectAttempts)
        } else {
            ConnectionState.Connecting
        }
        try {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            log("Connecting to $address")
        } catch (_: SecurityException) {
            fail("Bluetooth permission is missing")
        }
    }

    private fun onLinkLost(g: BluetoothGatt, status: Int) {
        g.close()
        gatt = null
        clearOperations()
        log("Link lost (GATT status $status)")
        val address = targetAddress
        val nextAttempt = reconnectAttempts + 1
        when {
            userRequestedDisconnect -> connectionState.value = ConnectionState.Disconnected
            address != null && nextAttempt <= MAX_RECONNECT_ATTEMPTS -> {
                reconnectAttempts = nextAttempt
                connectionState.value = ConnectionState.Reconnecting(nextAttempt)
                handler.postDelayed(reconnectRunnable, RECONNECT_BASE_DELAY_MILLIS shl (nextAttempt - 1))
            }
            else -> fail("Connection lost (GATT status $status)")
        }
    }

    private fun onServicesReady(g: BluetoothGatt) {
        val service = g.getService(BleContract.SERVICE_UUID)
        val characteristics = Channel.entries.associateWith { channel ->
            service?.getCharacteristic(BleContract.uuidFor(channel))
        }
        diagnosticsState.update { current ->
            current.copy(
                services = g.services.map { found ->
                    GattServiceInfo(found.uuid.toString(), found.characteristics.map { it.uuid.toString() })
                },
                missing = characteristics.filterValues { it == null }.keys,
                serviceFound = service != null,
            )
        }
        if (service == null) {
            userRequestedDisconnect = true
            gatt = null
            g.disconnect()
            fail("ChildMonitor service ${BleContract.SERVICE_UUID} was not found on this device")
            return
        }
        reconnectAttempts = 0
        connectionState.value = ConnectionState.Connected
        val found = characteristics.values.filterNotNull()
        log("Service found, ${found.size}/${characteristics.size} characteristics available")
        enqueue(found.map { GattOperation.Subscribe(it) } + found.map { GattOperation.Read(it) })
    }

    private fun handleRead(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            publish(characteristic, value)
        } else {
            log("Read failed for ${characteristic.uuid} (status $status)")
        }
        onOperationFinished()
    }

    private fun publish(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val channel = BleContract.channelFor(characteristic.uuid) ?: return
        payloadFlow.tryEmit(RawPayload(channel, value, clock()))
    }

    private fun addDevice(result: ScanResult) {
        val device = ScannedDevice(
            address = result.device.address,
            name = result.scanRecord?.deviceName ?: result.device.name,
            rssi = result.rssi,
        )
        deviceList.update { current ->
            (current.filterNot { it.address == device.address } + device).sortedByDescending { it.rssi }
        }
    }

    private fun enqueue(newOperations: List<GattOperation>) = synchronized(lock) {
        operations.addAll(newOperations)
        drain()
    }

    private fun onOperationFinished() = synchronized(lock) {
        operationInFlight = false
        drain()
    }

    private fun clearOperations() = synchronized(lock) {
        operations.clear()
        operationInFlight = false
    }

    private fun drain() {
        if (operationInFlight) return
        val g = gatt ?: return
        val operation = operations.removeFirstOrNull() ?: return
        operationInFlight = true
        val started = try {
            execute(g, operation)
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            operationInFlight = false
            log("GATT operation was rejected: ${operation.characteristic.uuid}")
            drain()
        }
    }

    private fun execute(g: BluetoothGatt, operation: GattOperation): Boolean = when (operation) {
        is GattOperation.Read -> g.readCharacteristic(operation.characteristic)
        is GattOperation.Subscribe -> {
            val descriptor = operation.characteristic.getDescriptor(BleContract.CCCD_UUID)
            g.setCharacteristicNotification(operation.characteristic, true) &&
                descriptor != null &&
                writeDescriptor(g, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            g.writeDescriptor(descriptor)
        }

    private fun fail(message: String) {
        connectionState.value = ConnectionState.Error(message)
        log(message)
    }

    private fun log(message: String) {
        diagnosticsState.update { current ->
            current.copy(events = (listOf(DiagnosticEvent(clock(), message)) + current.events).take(MAX_EVENTS))
        }
    }

    private sealed interface GattOperation {
        val characteristic: BluetoothGattCharacteristic

        data class Subscribe(override val characteristic: BluetoothGattCharacteristic) : GattOperation
        data class Read(override val characteristic: BluetoothGattCharacteristic) : GattOperation
    }

    private companion object {
        const val SCAN_TIMEOUT_MILLIS = 20_000L
        const val MAX_RECONNECT_ATTEMPTS = 3
        const val RECONNECT_BASE_DELAY_MILLIS = 2_000L
        const val PAYLOAD_BUFFER = 64
        const val MAX_EVENTS = 50
    }
}
