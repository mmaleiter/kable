package com.juul.kable

import com.benasher44.uuid.Uuid
import com.juul.kable.CentralManagerDelegate.ConnectionEvent
import com.juul.kable.CentralManagerDelegate.ConnectionEvent.DidConnect
import com.juul.kable.CentralManagerDelegate.ConnectionEvent.DidDisconnect
import com.juul.kable.CentralManagerDelegate.ConnectionEvent.DidFailToConnect
import com.juul.kable.PeripheralDelegate.DidUpdateValueForCharacteristic
import com.juul.kable.PeripheralDelegate.DidUpdateValueForCharacteristic.Closed
import com.juul.kable.PeripheralDelegate.DidUpdateValueForCharacteristic.Data
import com.juul.kable.PeripheralDelegate.DidUpdateValueForCharacteristic.Error
import com.juul.kable.PeripheralDelegate.Response.DidDiscoverCharacteristicsForService
import com.juul.kable.PeripheralDelegate.Response.DidDiscoverServices
import com.juul.kable.PeripheralDelegate.Response.DidReadRssi
import com.juul.kable.PeripheralDelegate.Response.DidUpdateNotificationStateForCharacteristic
import com.juul.kable.PeripheralDelegate.Response.DidUpdateValueForDescriptor
import com.juul.kable.PeripheralDelegate.Response.DidWriteValueForCharacteristic
import com.juul.kable.State.Disconnected.Status.Cancelled
import com.juul.kable.State.Disconnected.Status.ConnectionLimitReached
import com.juul.kable.State.Disconnected.Status.EncryptionTimedOut
import com.juul.kable.State.Disconnected.Status.Failed
import com.juul.kable.State.Disconnected.Status.PeripheralDisconnected
import com.juul.kable.State.Disconnected.Status.Timeout
import com.juul.kable.State.Disconnected.Status.Unknown
import com.juul.kable.State.Disconnected.Status.UnknownDevice
import com.juul.kable.WriteType.WithResponse
import com.juul.kable.WriteType.WithoutResponse
import com.juul.kable.logs.Logger
import com.juul.kable.logs.Logging
import com.juul.kable.logs.detail
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBErrorConnectionFailed
import platform.CoreBluetooth.CBErrorConnectionLimitReached
import platform.CoreBluetooth.CBErrorConnectionTimeout
import platform.CoreBluetooth.CBErrorEncryptionTimedOut
import platform.CoreBluetooth.CBErrorOperationCancelled
import platform.CoreBluetooth.CBErrorPeripheralDisconnected
import platform.CoreBluetooth.CBErrorUnknownDevice
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

public actual fun CoroutineScope.peripheral(
    advertisement: Advertisement,
    builderAction: PeripheralBuilderAction,
): Peripheral = peripheral(advertisement.cbPeripheral, builderAction)

public fun CoroutineScope.peripheral(
    identifier: Identifier,
    builderAction: PeripheralBuilderAction = {},
): Peripheral {
    val cbPeripheral = CentralManager.Default.retrievePeripheral(identifier)
        ?: throw NoSuchElementException("Peripheral with UUID $identifier not found")
    return peripheral(cbPeripheral, builderAction)
}

public fun CoroutineScope.peripheral(
    cbPeripheral: CBPeripheral,
    builderAction: PeripheralBuilderAction,
): CoreBluetoothPeripheral {
    val builder = PeripheralBuilder()
    builder.builderAction()
    return CBPeripheralCoreBluetoothPeripheral(
        coroutineContext,
        cbPeripheral,
        builder.observationExceptionHandler,
        builder.onServicesDiscovered,
        builder.logging,
    )
}

internal class CBPeripheralCoreBluetoothPeripheral(
    parentCoroutineContext: CoroutineContext,
    internal val cbPeripheral: CBPeripheral,
    observationExceptionHandler: ObservationExceptionHandler,
    private val onServicesDiscovered: ServicesDiscoveredAction,
    private val logging: Logging,
) : CoreBluetoothPeripheral {

    private val job = SupervisorJob(parentCoroutineContext.job) // todo: Disconnect/dispose CBPeripheral on completion?
    private val scope = CoroutineScope(parentCoroutineContext + job + CoroutineName("Kable/Peripheral@${cbPeripheral.identifier.UUIDString}"))
    internal val connectionScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]) + CoroutineName("Kable/Connect@${cbPeripheral.identifier.UUIDString}"))

    private val centralManager: CentralManager = CentralManager.Default

    private val logger = Logger(logging, identifier = cbPeripheral.identifier.UUIDString)

    private val _state = MutableStateFlow<State>(State.Disconnected())
    override val state: StateFlow<State> = _state.asStateFlow()

    private val observers = Observers<NSData>(this, logging, exceptionHandler = observationExceptionHandler)

    internal val platformIdentifier = cbPeripheral.identifier

    init {
        centralManager.delegate
            .state
            .filter { state -> state == CBManagerStatePoweredOff }
            .onEach {
                disconnect()
                _state.value = State.Disconnected()
            }
            .launchIn(scope)

        centralManager.delegate
            .connectionState
            .filter { event -> event.identifier == cbPeripheral.identifier }
            .onEach { event ->
                logger.debug {
                    message = "CentralManagerDelegate state change"
                    detail("state", event.toString())
                }
            }
            .map { event -> event.toState() }
            .onEach { _state.value = it }
            .launchIn(scope)
    }

    internal val canSendWriteWithoutResponse = MutableStateFlow(cbPeripheral.canSendWriteWithoutResponse)

    private val _discoveredServices = atomic<List<DiscoveredService>?>(null)
    private val discoveredServices: List<DiscoveredService>
        get() = _discoveredServices.value
            ?: throw IllegalStateException("Services have not been discovered for $this")

    override val services: List<DiscoveredService>?
        get() = _discoveredServices.value?.toList()

    private val _connection = atomic<Connection?>(null)
    private val connection: Connection
        inline get() = _connection.value ?: throw NotReadyException(toString())

    private val connectJob = atomic<Deferred<Unit>?>(null)

    override val name: String? get() = cbPeripheral.name

    private fun onDisconnected() {
        logger.info { message = "Disconnected" }
        connectJob.value?.cancel()
        connectJob.value = null
        _connection.value?.close()
        _connection.value = null
    }

    private fun connectAsync() = connectionScope.async(start = LAZY) {
        logger.info { message = "Connecting" }
        _state.value = State.Connecting.Bluetooth

        centralManager.delegate.onDisconnected.onEach { identifier ->
            if (identifier == cbPeripheral.identifier) onDisconnected()
        }.launchIn(connectionScope)

        try {
            val connection = centralManager.connectPeripheral(this@CBPeripheralCoreBluetoothPeripheral, logging).also {
                _connection.value = it
            }

            connection
                .delegate
                .characteristicChanges
                .takeWhile { it !== Closed }
                .mapNotNull { it as? Data }
                .map {
                    ObservationEvent.CharacteristicChange(
                        characteristic = it.cbCharacteristic.toLazyCharacteristic(),
                        data = it.data,
                    )
                }
                .onEach(observers.characteristicChanges::emit)
                .launchIn(connectionScope, start = UNDISPATCHED)

            // fixme: Handle centralManager:didFailToConnectPeripheral:error:
            // https://developer.apple.com/documentation/corebluetooth/cbcentralmanagerdelegate/1518988-centralmanager
            suspendUntil<State.Connecting.Services>()
            discoverServices()
            onServicesDiscovered(ServicesDiscoveredPeripheral(this@CBPeripheralCoreBluetoothPeripheral))

            _state.value = State.Connecting.Observes
            logger.verbose { message = "Configuring characteristic observations" }
            observers.onConnected()
        } catch (e: Exception) {
            logger.error(e) { message = "Failed to connect" }
            withContext(NonCancellable) {
                centralManager.cancelPeripheralConnection(cbPeripheral)
                _connection.value = null
            }
            throw e
        }

        logger.info { message = "Connected" }
        _state.value = State.Connected
    }

    override suspend fun connect() {
        // Check CBCentral State since connecting can result in an api misuse message
        centralManager.checkBluetoothState(CBManagerStatePoweredOn)
        connectJob.updateAndGet { it ?: connectAsync() }!!.await()
    }

    override suspend fun disconnect() {
        try {
            connectionScope.coroutineContext.job.cancelAndJoinChildren()
        } finally {
            withContext(NonCancellable) {
                centralManager.cancelPeripheralConnection(cbPeripheral)
            }
            onDisconnected()
        }
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun rssi(): Int = connection.execute<DidReadRssi> {
        centralManager.readRssi(cbPeripheral)
    }.rssi.intValue

    private suspend fun discoverServices(): Unit = discoverServices(services = null)

    /** @param services to discover (list of service UUIDs), or `null` for all. */
    private suspend fun discoverServices(
        services: List<Uuid>?,
    ) {
        logger.verbose { message = "discoverServices" }
        val servicesToDiscover = services?.map { CBUUID.UUIDWithNSUUID(it.toNSUUID()) }

        connection.execute<DidDiscoverServices> {
            centralManager.discoverServices(cbPeripheral, servicesToDiscover)
        }

        cbPeripheral.services?.forEach { cbService ->
            connection.execute<DidDiscoverCharacteristicsForService> {
                centralManager.discoverCharacteristics(cbPeripheral, cbService as CBService)
            }
        }

        _discoveredServices.value = cbPeripheral.services
            .orEmpty()
            .map { it as PlatformService }
            .map(::DiscoveredService)
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun write(
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType,
    ): Unit = write(characteristic, data.toNSData(), writeType)

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun write(
        characteristic: Characteristic,
        data: NSData,
        writeType: WriteType,
    ) {
        logger.debug {
            message = "write"
            detail(characteristic)
            detail(writeType)
            detail(data)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, writeType.properties)
        when (writeType) {
            WithResponse -> connection.execute<DidWriteValueForCharacteristic> {
                centralManager.write(cbPeripheral, data, platformCharacteristic, CBCharacteristicWriteWithResponse)
            }
            WithoutResponse -> connection.semaphore.withPermit {
                if (!canSendWriteWithoutResponse.updateAndGet { cbPeripheral.canSendWriteWithoutResponse }) {
                    canSendWriteWithoutResponse.first { it }
                }
                centralManager.write(cbPeripheral, data, platformCharacteristic, CBCharacteristicWriteWithoutResponse)
            }
        }
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun read(
        characteristic: Characteristic,
    ): ByteArray = readAsNSData(characteristic).toByteArray()

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun readAsNSData(
        characteristic: Characteristic,
    ): NSData {
        logger.debug {
            message = "read"
            detail(characteristic)
        }

        val connection = this.connection
        val platformCharacteristic = discoveredServices.obtain(characteristic, Read)

        return connection.semaphore.withPermit {
            connection
                .delegate
                .characteristicChanges
                .onSubscription { centralManager.read(cbPeripheral, platformCharacteristic) }
                .firstOrThrow { it.cbCharacteristic.UUID == platformCharacteristic.UUID }
        }
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun write(
        descriptor: Descriptor,
        data: ByteArray,
    ): Unit = write(descriptor, data.toNSData())

    @Throws(CancellationException::class, IOException::class)
    override suspend fun write(
        descriptor: Descriptor,
        data: NSData,
    ) {
        logger.debug {
            message = "write"
            detail(descriptor)
            detail(data)
        }

        val platformDescriptor = discoveredServices.obtain(descriptor)
        connection.execute<DidUpdateValueForDescriptor> {
            centralManager.write(cbPeripheral, data, platformDescriptor)
        }
    }

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun read(
        descriptor: Descriptor,
    ): ByteArray = readAsNSData(descriptor).toByteArray()

    @Throws(CancellationException::class, IOException::class, NotReadyException::class)
    override suspend fun readAsNSData(
        descriptor: Descriptor,
    ): NSData {
        logger.debug {
            message = "read"
            detail(descriptor)
        }

        val platformDescriptor = discoveredServices.obtain(descriptor)
        return connection.execute<DidUpdateValueForDescriptor> {
            centralManager.read(cbPeripheral, platformDescriptor)
        }.descriptor.value as NSData
    }

    override fun observe(
        characteristic: Characteristic,
        onSubscription: OnSubscriptionAction,
    ): Flow<ByteArray> = observeAsNSData(characteristic, onSubscription).map(NSData::toByteArray)

    override fun observeAsNSData(
        characteristic: Characteristic,
        onSubscription: OnSubscriptionAction,
    ): Flow<NSData> = observers.acquire(characteristic, onSubscription)

    internal suspend fun startNotifications(characteristic: Characteristic) {
        logger.debug {
            message = "CentralManager.notify"
            detail(characteristic)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, Notify or Indicate)
        connection.execute<DidUpdateNotificationStateForCharacteristic> {
            centralManager.notify(cbPeripheral, platformCharacteristic)
        }
    }

    internal suspend fun stopNotifications(characteristic: Characteristic) {
        logger.debug {
            message = "CentralManager.cancelNotify"
            detail(characteristic)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, Notify or Indicate)
        connection.execute<DidUpdateNotificationStateForCharacteristic> {
            centralManager.cancelNotify(cbPeripheral, platformCharacteristic)
        }
    }

    override fun toString(): String = "Peripheral(cbPeripheral=$cbPeripheral)"
}

private suspend fun Flow<DidUpdateValueForCharacteristic>.firstOrThrow(
    predicate: suspend (Data) -> Boolean,
): NSData = when (val value = first { it !is Data || predicate.invoke(it) }) {
    is Data -> value.data
    is Error -> throw IOException(value.error.description, cause = null)
    Closed -> throw ConnectionLostException()
}

private fun ConnectionEvent.toState(): State = when (this) {
    is DidConnect -> State.Connecting.Services
    is DidFailToConnect -> State.Disconnected(error?.toStatus())
    is DidDisconnect -> State.Disconnected(error?.toStatus())
}

private fun NSError.toStatus(): State.Disconnected.Status = when (code) {
    CBErrorPeripheralDisconnected -> PeripheralDisconnected
    CBErrorConnectionFailed -> Failed
    CBErrorConnectionTimeout -> Timeout
    CBErrorUnknownDevice -> UnknownDevice
    CBErrorOperationCancelled -> Cancelled
    CBErrorConnectionLimitReached -> ConnectionLimitReached
    CBErrorEncryptionTimedOut -> EncryptionTimedOut
    else -> Unknown(code.toInt())
}

private fun CentralManager.checkBluetoothState(expected: CBManagerState) {
    val actual = delegate.state.value
    if (expected != actual) {
        fun nameFor(value: Number) = when (value) {
            CBManagerStatePoweredOff -> "PoweredOff"
            CBManagerStatePoweredOn -> "PoweredOn"
            CBManagerStateResetting -> "Resetting"
            CBManagerStateUnauthorized -> "Unauthorized"
            CBManagerStateUnknown -> "Unknown"
            CBManagerStateUnsupported -> "Unsupported"
            else -> "Unknown"
        }
        val actualName = nameFor(actual)
        val expectedName = nameFor(expected)
        throw BluetoothDisabledException("Bluetooth state is $actualName ($actual), but $expectedName ($expected) was required.")
    }
}
