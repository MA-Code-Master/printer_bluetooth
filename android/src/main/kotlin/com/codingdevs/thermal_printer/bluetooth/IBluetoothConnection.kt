package com.codingdevs.thermal_printer.bluetooth
import io.flutter.plugin.common.MethodChannel.Result

interface IBluetoothConnection {
    fun connect(address: String, result: Result)
    fun stop()

    /**
     * Writes [out] to the device, returning whether it was actually accepted.
     *
     * MALY-POS FORK: this used to return Unit, so a write that threw IOException
     * was indistinguishable from one that succeeded. See BluetoothConnection.write.
     */
    fun write(out: ByteArray?): Boolean
    var state: Int
}