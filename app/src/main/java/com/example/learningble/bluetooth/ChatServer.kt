package com.example.learningble.bluetooth

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.learningble.models.Message
import com.example.learningble.states.DeviceConnectionState
import com.example.learningble.utils.MESSAGE_UUID
import com.example.learningble.utils.SERVICE_UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private const val TAG = "ChatServerTAG"

object ChatServer {


    private var app: Application? = null
    private lateinit var bluetoothManager: BluetoothManager

    private var adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseSettings: AdvertiseSettings = buildAdvertiseSettings()
    private var advertiseData: AdvertiseData = buildAdvertiseData()

    private val _messages = MutableLiveData<Message>()
    val messages = _messages as LiveData<Message>


    private var gattServer: BluetoothGattServer? = null
    private var gattServerCallback: BluetoothGattServerCallback? = null

    private var gattClient: BluetoothGatt? = null
    private var gattClientCallback: BluetoothGattCallback? = null

    private var currentDevice: BluetoothDevice? = null

    private val _deviceConnection = MutableLiveData<DeviceConnectionState>()
    val deviceConnection = _deviceConnection as LiveData<DeviceConnectionState>

    private var gatt: BluetoothGatt? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null

    private var count = 0

    fun startServer(app: Application) {
        bluetoothManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bluetoothManager.adapter
        setupGattServer(app)
        startAdvertisement()

        println("ChatServer started")
    }

    fun stopServer() {
        stopAdvertising()
    }


    fun setCurrentChatConnection(device: BluetoothDevice) {
        currentDevice = device
        _deviceConnection.postValue(DeviceConnectionState.Connected(device))
        connectToChatDevice(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectToChatDevice(device: BluetoothDevice) {
        gattClientCallback = GattClientCallback()
        gattClient = device.connectGatt(app, false, gattClientCallback)
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(message: String): Boolean {

        messageCharacteristic?.let { characteristic ->
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            val messageBytes = message.toByteArray(Charsets.UTF_8)
            characteristic.value = messageBytes
            gatt?.let {
                val success = it.writeCharacteristic(messageCharacteristic)
                if (success) {
                    _messages.postValue(Message.LocalMessage(message))
                }
            }
        }

        return false
    }


    @SuppressLint("MissingPermission")
    fun setupGattServer(app: Application) {
        Log.i(TAG,"setupGattServer")
        gattServerCallback = GattServerCallback()

        gattServer = bluetoothManager.openGattServer(
            app,
            gattServerCallback
        ).apply {
            addService(setupGattService())
        }
    }

    private fun setupGattService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val messageCharacteristic = BluetoothGattCharacteristic(
            MESSAGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(messageCharacteristic)

        return service
    }

    @SuppressLint("MissingPermission")
    fun startAdvertisement() { // Broadcast the service UUID over BLE so it can be found.
        advertiser = adapter.bluetoothLeAdvertiser

        if (advertiseCallback == null) {
            advertiseCallback = DeviceAdvertiseCallback()

            advertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        Log.d(TAG, "Stopping Advertising with advertiser $advertiser")
        if(advertiseCallback != null)
            advertiser?.stopAdvertising(advertiseCallback)
        advertiseCallback = null
    }


    private fun buildAdvertiseData(): AdvertiseData {
        val dataBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)

        return dataBuilder.build()
    }


    private fun buildAdvertiseSettings(): AdvertiseSettings {
        return AdvertiseSettings.Builder()
//            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTimeout(0)
            .build()
    }

    private class GattServerCallback : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            val isSuccess = status == BluetoothGatt.GATT_SUCCESS
            val isConnected = newState == BluetoothProfile.STATE_CONNECTED

            if (isSuccess && isConnected) {
                setCurrentChatConnection(device)
            } else {
                _deviceConnection.postValue(DeviceConnectionState.Disconnected)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            super.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )
            if (characteristic.uuid == MESSAGE_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                val message = value?.toString(Charsets.UTF_8)

                CoroutineScope(Dispatchers.IO).launch {
                    message?.let {
                        _messages.postValue(Message.RemoteMessage(it))
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            gattServer?.sendResponse(device, requestId, 1, offset, characteristic.value)
        }
    }

    private class GattClientCallback : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            val isSuccess = status == BluetoothGatt.GATT_SUCCESS
            val isConnected = newState == BluetoothProfile.STATE_CONNECTED

            if (isSuccess && isConnected) {
                gatt.discoverServices()
            }

//            // Handle disconnect
//            if(!isSuccess && newState == BluetoothProfile.STATE_DISCONNECTED) {
//                println("Disconnected")
//                _deviceConnection.postValue(DeviceConnectionState.Disconnected)
//            }
            if(isSuccess && newState == BluetoothProfile.STATE_DISCONNECTED) {
                println("Disconnected")
                _deviceConnection.postValue(DeviceConnectionState.Disconnected)
            }

        }

        override fun onServicesDiscovered(discoveredGatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(discoveredGatt, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt = discoveredGatt
                val service = discoveredGatt.getService(SERVICE_UUID)

                if(service != null)
                    messageCharacteristic = service.getCharacteristic(MESSAGE_UUID)
                else
                    Log.e(TAG, "Service not found")
            }
        }
    }

    private class DeviceAdvertiseCallback : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            val errorMessage = "Advertise failed with error: $errorCode"
            Log.d(TAG, "failed $errorMessage")
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Advertise successfully started")
        }
    }
}