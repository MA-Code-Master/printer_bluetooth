package com.codingdevs.thermal_printer

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityCompat.startActivityForResult
import com.codingdevs.thermal_printer.bluetooth.BluetoothConnection
import com.codingdevs.thermal_printer.bluetooth.BluetoothConstants
import com.codingdevs.thermal_printer.bluetooth.BluetoothService
import com.codingdevs.thermal_printer.bluetooth.BluetoothService.Companion.TAG
import com.codingdevs.thermal_printer.usb.USBPrinterService
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/** ThermalPrinterPlatformPlugin */
class ThermalPrinterPlugin : FlutterPlugin, MethodCallHandler, PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    ActivityAware {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel

    private var messageChannel: EventChannel? = null
    private var messageUSBChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null

    // Declare our eventSink later it will be initialized
    private var eventUSBSink: EventChannel.EventSink? = null
    private var context: Context? = null
    private var currentActivity: Activity? = null
    private var requestPermissionBT: Boolean = false
    private var isBle: Boolean = false
    private var isScan: Boolean = false
    lateinit var adapter: USBPrinterService
    private lateinit var bluetoothService: BluetoothService


    private val usbHandler = object : Handler(Looper.getMainLooper()) {

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {

                USBPrinterService.STATE_USB_CONNECTED -> {
                    eventUSBSink?.success(2)
                }
                USBPrinterService.STATE_USB_CONNECTING -> {
                    eventUSBSink?.success(1)
                }
                USBPrinterService.STATE_USB_NONE -> {
                    eventUSBSink?.success(0)
                }
            }
        }
    }


    private val bluetoothHandler = object : Handler(Looper.getMainLooper()) {

        private val bluetoothStatus: Int
            get() = BluetoothService.bluetoothConnection?.state ?: 99

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                BluetoothConstants.MESSAGE_STATE_CHANGE -> {
                    when (bluetoothStatus) {
                        BluetoothConstants.STATE_CONNECTED -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_CONNECTED ")
                            if (msg.obj != null)
                                try {
                                    val result = msg.obj as Result?
                                    result?.success(true)
                                } catch (e: Exception) {
                                }
                            eventSink?.success(2)
                            bluetoothService.removeReconnectHandlers()
                        }
                        BluetoothConstants.STATE_CONNECTING -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_CONNECTING ")
                            eventSink?.success(1)
                        }
                        BluetoothConstants.STATE_NONE -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_NONE ")
                            eventSink?.success(0)
                            bluetoothService.autoConnectBt()

                        }
                        BluetoothConstants.STATE_FAILED -> {
                            Log.w(TAG, " -------------------------- connection BT STATE_FAILED ")
                            if (msg.obj != null)
                                try {
                                    val result = msg.obj as Result?
                                    result?.success(false)
                                } catch (e: Exception) {
                                }
                            eventSink?.success(0)
                        }
                    }
                }
                BluetoothConstants.MESSAGE_WRITE -> {
//                val readBuf = msg.obj as ByteArray
//                Log.d("bluetooth", "envia bt: ${String(readBuf)}")
                }
                BluetoothConstants.MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    var readMessage = String(readBuf, 0, msg.arg1)
                    readMessage = readMessage.trim { it <= ' ' }
                    Log.d("bluetooth", "receive bt: $readMessage")
                }
                BluetoothConstants.MESSAGE_DEVICE_NAME -> {
                    val deviceName = msg.data.getString(BluetoothConstants.DEVICE_NAME)
                    Log.d("bluetooth", " ------------- deviceName $deviceName -----------------")
                }

                BluetoothConstants.MESSAGE_TOAST -> {
                    val bundle = msg.data
                    bundle?.getInt(BluetoothConnection.TOAST)?.let {
                        Toast.makeText(context, context!!.getString(it), Toast.LENGTH_SHORT).show()
                    }
                }
                BluetoothConstants.MESSAGE_START_SCANNING -> {

                }

                BluetoothConstants.MESSAGE_STOP_SCANNING -> {

                }
                99 -> {

                }

            }
        }

    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        messageChannel?.setStreamHandler(null)
        messageUSBChannel?.setStreamHandler(null)

        messageChannel = null
        messageUSBChannel = null

        bluetoothService.setHandler(null)
        adapter.setHandler(null)
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, methodChannel)
        channel.setMethodCallHandler(this)

        messageChannel = EventChannel(flutterPluginBinding.binaryMessenger, eventChannelBT)
        messageChannel?.setStreamHandler(object : EventChannel.StreamHandler {

            override fun onListen(p0: Any?, sink: EventChannel.EventSink) {
                eventSink = sink
            }

            override fun onCancel(p0: Any?) {
                eventSink = null
            }
        })

        messageUSBChannel = EventChannel(flutterPluginBinding.binaryMessenger, eventChannelUSB)
        messageUSBChannel?.setStreamHandler(object : EventChannel.StreamHandler {

            override fun onListen(p0: Any?, sink: EventChannel.EventSink) {
                eventUSBSink = sink
            }

            override fun onCancel(p0: Any?) {
                eventUSBSink = null
            }
        })

        context = flutterPluginBinding.applicationContext
        adapter = USBPrinterService.getInstance(usbHandler)
        adapter.init(context)

        bluetoothService = BluetoothService.getInstance(bluetoothHandler)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        isScan = false
        when {
            call.method.equals("getBluetoothList") -> {
                isBle = false
                isScan = true
                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.cleanHandlerBtBle()
                    bluetoothService.scanBluDevice(channel)
                    result.success(null)
                }
            }
            call.method.equals("getBluetoothLeList") -> {
                isBle = true
                isScan = true
                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.scanBleDevice(channel)
                    result.success(null)
                }
            }

            call.method.equals("onStartConnection") -> {
                val address: String? = call.argument("address")
                val isBle: Boolean? = call.argument("isBle")
                val autoConnect: Boolean = if (call.hasArgument("autoConnect")) call.argument("autoConnect")!! else false
                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.setHandler(bluetoothHandler)
                    bluetoothService.onStartConnection(context!!, address!!, result, isBle = isBle!!, autoConnect = autoConnect)
                } else {
                    result.success(false)
                }
            }

            call.method.equals("disconnect") -> {
                try {
                    bluetoothService.setHandler(bluetoothHandler)
                    bluetoothService.bluetoothDisconnect()
                    result.success(true)
                } catch (e: Exception) {
                    result.success(false)
                }

            }

            call.method.equals("sendDataByte") -> {
                if (verifyIsBluetoothIsOn()) {
                    bluetoothService.setHandler(bluetoothHandler)
                    // MALY-POS FORK: accept a ByteArray directly.
                    //
                    // Dart now sends a Uint8List, which the codec delivers here
                    // as a ByteArray with no per-byte boxing. The List branch is
                    // kept so an older caller still works.
                    val bytes: ByteArray? = when (val raw = call.argument<Any>("bytes")) {
                        is ByteArray -> raw
                        is List<*> -> ByteArray(raw.size) { i -> (raw[i] as Number).toByte() }
                        else -> null
                    }
                    if (bytes == null) {
                        Log.w(TAG, "sendDataByte: missing or unusable 'bytes' argument")
                        result.success(false)
                    } else {
                        result.success(bluetoothService.sendDataByte(bytes))
                    }
                } else {
                    result.success(false)
                }
            }
            call.method.equals("sendText") -> {
                if (verifyIsBluetoothIsOn()) {
                    val text: String? = call.argument("text")
                    bluetoothService.sendData(text!!)
                    result.success(true)
                } else {
                    result.success(false)
                }
            }
            call.method.equals("getList") -> {
                bluetoothService.cleanHandlerBtBle()
                getUSBDeviceList(result)
            }
            call.method.equals("connectPrinter") -> {
                val vendor: Int? = call.argument("vendor")
                val product: Int? = call.argument("product")
                connectPrinter(vendor, product, result)
            }
            call.method.equals("close") -> {
                closeConn(result)
            }
            call.method.equals("printText") -> {
                val text: String? = call.argument("text")
                printText(text, result)
            }
            call.method.equals("printRawData") -> {
                val raw: String? = call.argument("raw")
                printRawData(raw, result)
            }
            call.method.equals("printBytes") -> {
                val bytes: ArrayList<Int>? = call.argument("bytes")
                printBytes(bytes, result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    /**
     *
     */

    private fun verifyIsBluetoothIsOn(): Boolean {
        if (checkPermissions()) {
            if (!bluetoothService.mBluetoothAdapter.isEnabled) {
                if (requestPermissionBT) return false
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                currentActivity?.let { startActivityForResult(it, enableBtIntent, PERMISSION_ENABLE_BLUETOOTH, null) }
                requestPermissionBT = true
                return false
            }
        } else return false
        return true
    }

    private fun getUSBDeviceList(result: Result) {
        val usbDevices: List<UsbDevice> = adapter.deviceList
        val list = ArrayList<HashMap<*, *>>()
        for (usbDevice in usbDevices) {
            val deviceMap: HashMap<String?, String?> = HashMap()
            deviceMap["name"] = usbDevice.deviceName
            deviceMap["manufacturer"] = usbDevice.manufacturerName
            deviceMap["product"] = usbDevice.productName
            deviceMap["deviceId"] = usbDevice.deviceId.toString()
            deviceMap["vendorId"] = usbDevice.vendorId.toString()
            deviceMap["productId"] = usbDevice.productId.toString()
            list.add(deviceMap)
        }
        result.success(list)
    }

    private fun connectPrinter(vendorId: Int?, productId: Int?, result: Result) {
        if (vendorId == null || productId == null) return
        adapter.setHandler(usbHandler)
        if (!adapter.selectDevice(vendorId, productId)) {
            result.success(false)
        } else {
            result.success(true)
        }
    }

    private fun closeConn(result: Result) {
        adapter.setHandler(usbHandler)
        adapter.closeConnectionIfExists()
        result.success(true)
    }

    private fun printText(text: String?, result: Result) {
        if (text.isNullOrEmpty()) return
        adapter.setHandler(usbHandler)
        adapter.printText(text)
        result.success(true)
    }

    private fun printRawData(base64Data: String?, result: Result) {
        if (base64Data.isNullOrEmpty()) return
        adapter.setHandler(usbHandler)
        adapter.printRawData(base64Data)
        result.success(true)
    }

    private fun printBytes(bytes: ArrayList<Int>?, result: Result) {
        if (bytes == null) return
        adapter.setHandler(usbHandler)
        adapter.printBytes(bytes)
        result.success(true)
    }

    /**
     * MALY-POS FORK — ACCESS_FINE_LOCATION is no longer demanded on Android 12+.
     *
     * Upstream required it on every Android version before it would list or
     * connect to a single Bluetooth device, and requested it *without*
     * ACCESS_COARSE_LOCATION. Android 12 made that combination a no-op: a
     * request for FINE that does not also ask for COARSE is ignored outright —
     * no dialog, no grant. So from API 31 on this method could never pass, and
     * `getBluetoothList` / `onStartConnection` returned **without answering the
     * method call**. From Dart that is indistinguishable from "no printers are
     * paired": the discovery stream just stays empty until it times out. Old
     * Android devices were unaffected, which is why Bluetooth printers worked
     * there and nowhere else.
     *
     * The permission was never actually needed for what this plugin does on
     * modern Android: BLUETOOTH_SCAN is declared `neverForLocation`, and
     * listing bonded devices is a lookup, not a radio scan. Below API 31 it
     * still is required by the platform, so it is still requested there.
     *
     * Also stops crashing when there is no attached Activity: requestPermissions
     * on a null activity threw an NPE inside the method-call handler, which the
     * unawaited invokeMethod on the Dart side swallowed without trace.
     */
    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (!hasPermissions(context, *permissions.toTypedArray())) {
            val activity = currentActivity
            if (activity == null) {
                Log.w(TAG, "checkPermissions: no activity attached, cannot request $permissions")
                return false
            }
            ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), PERMISSION_ALL)
            return false
        }
        return true
    }

    private fun hasPermissions(context: Context?, vararg permissions: String?): Boolean {
        if (context != null) {
            for (permission in permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission!!) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
        }
        return true
    }


    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
        bluetoothService.setActivity(currentActivity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        currentActivity = null
        bluetoothService.setActivity(null)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addRequestPermissionsResultListener(this)
        binding.addActivityResultListener(this)
        bluetoothService.setActivity(currentActivity)
    }

    override fun onDetachedFromActivity() {
        currentActivity = null
        bluetoothService.setActivity(null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            PERMISSION_ENABLE_BLUETOOTH -> {
                requestPermissionBT = false

                Log.d(TAG, "PERMISSION_ENABLE_BLUETOOTH PERMISSION_GRANTED resultCode $resultCode")
                if (resultCode == Activity.RESULT_OK)
                    if (isScan)
                        if (isBle) bluetoothService.scanBleDevice(channel) else bluetoothService.scanBluDevice(channel)

            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        Log.d(TAG, " --- requestCode $requestCode")
        when (requestCode) {

            PERMISSION_ALL -> {
                var grant = true
                grantResults.forEach { permission ->

                    val permissionGranted = grantResults.isNotEmpty() &&
                            permission == PackageManager.PERMISSION_GRANTED
                    Log.d(TAG, " --- requestCode $requestCode permission $permission permissionGranted $permissionGranted")
                    if (!permissionGranted) grant = false

                }
                if (!grant) {
                    // MALY-POS FORK: no toast. The app requests these
                    // permissions itself before ever calling in here, and
                    // reports a refusal in the user's own language with a
                    // route into system settings — an English toast on top of
                    // that is noise.
                    Log.w(TAG, "Required permissions were not granted")
                } else {
                    if (verifyIsBluetoothIsOn() && isScan)
                        if (isBle) bluetoothService.scanBleDevice(channel) else bluetoothService.scanBluDevice(channel)
                }
                return true
            }
        }
        return false
    }

    companion object {
        const val PERMISSION_ALL = 1
        const val PERMISSION_ENABLE_BLUETOOTH = 999
        const val methodChannel = "com.codingdevs.thermal_printer"
        const val eventChannelBT = "com.codingdevs.thermal_printer/bt_state"
        const val eventChannelUSB = "com.codingdevs.thermal_printer/usb_state"

    }
}
