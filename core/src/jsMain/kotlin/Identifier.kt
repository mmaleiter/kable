package com.juul.kable

public actual typealias Identifier = String

public actual val Peripheral.identifier: Identifier
    get() = (this as BluetoothDeviceWebBluetoothPeripheral).platformIdentifier

public actual fun String.toIdentifier(): Identifier {
    return this
}
