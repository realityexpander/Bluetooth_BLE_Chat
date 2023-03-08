package com.realityexpander.learningble.viewmodels

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.realityexpander.learningble.bluetooth.ChatServer
import com.realityexpander.learningble.utils.SERVICE_UUID
import com.realityexpander.learningble.states.DeviceScanViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield


private const val TAG = "DeviceScanViewModel"

private const val SCAN_PERIOD = 20000L

class DeviceScanViewModel(app: Application) : AndroidViewModel(app) {


    private val _viewState = MutableLiveData<DeviceScanViewState>()
    val viewState = _viewState as LiveData<DeviceScanViewState>

    private val scanResults = mutableMapOf<String, BluetoothDevice>()

    private val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    private var scanner: BluetoothLeScanner? = null

    private var scanCallback: DeviceScanCallback? = null
    private lateinit var scanFilters: List<ScanFilter>
    private lateinit var scanSettings: ScanSettings

    @SuppressLint("MissingPermission")
    fun startScan() {
        scanFilters = buildScanFilters()
        scanSettings = buildScanSettings()
        if (!adapter.isMultipleAdvertisementSupported) {
            _viewState.value = DeviceScanViewState.AdvertisementNotSupported
            return
        }

        if (scanCallback == null) {
            scanner = adapter.bluetoothLeScanner
            _viewState.value = DeviceScanViewState.ActiveScan
            Handler().postDelayed({ stopScanning() }, SCAN_PERIOD)

            scanCallback = DeviceScanCallback()
            scanner?.startScan(scanFilters, scanSettings, scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        scanner?.stopScan(scanCallback)
        scanCallback = null
        _viewState.value = DeviceScanViewState.ScanResults(scanResults)
    }

    private fun buildScanFilters(): List<ScanFilter> {
        val builder = ScanFilter.Builder()
        builder.setServiceUuid(ParcelUuid(SERVICE_UUID))
        val filter = builder.build()
        return listOf(filter)
    }


    private fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
//            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }


    private inner class DeviceScanCallback : ScanCallback() {
        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            for (item in results) {
                item.device?.let { device ->
                    scanResults[device.address] = device
                }
            }
            Log.i(TAG, scanResults.toString())
            _viewState.value = DeviceScanViewState.ScanResults(scanResults)
        }
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            super.onScanResult(callbackType, result)
            result.device?.let { device ->
                scanResults[device.address] = device
            }
            Log.i(TAG, scanResults.toString())
            _viewState.value = DeviceScanViewState.ScanResults(scanResults)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorMessage = "Scan failed with error: $errorCode"
            _viewState.value = DeviceScanViewState.Error(errorMessage)
        }

    }

    override fun onCleared() {
        super.onCleared()
        stopScanning()
    }

    fun startPing() {
        viewModelScope.launch {

            while (true) {
                yield()
                ChatServer.sendMessage("ping:" + System.currentTimeMillis().toString().takeLast(6))
                //println("ping:" + System.currentTimeMillis().toString().takeLast(6))
                delay(1000)
            }
        }
    }
}