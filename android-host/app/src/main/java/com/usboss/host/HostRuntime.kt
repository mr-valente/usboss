package com.usboss.host

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object HostRuntime {
    const val ACTION_USB_PERMISSION = "com.usboss.host.USB_PERMISSION"
    private const val TAG = "USBoss"
    private const val PREFS_NAME = "usboss"
    private const val KEY_START_ON_BOOT = "start_on_boot"
    private const val KEY_VERBOSE_LOGGING = "verbose_logging"
    private const val REFRESH_LOOP_MS = 4_000L
    private const val MAX_RECENT_EVENTS = 80

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(HostUiState())
    val state = mutableState.asStateFlow()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var server: UsbBridgeServer? = null

    @Volatile
    private var candidatesById: Map<Int, UsbCandidate> = emptyMap()

    @Volatile
    private var initialized = false

    @Volatile
    private var refreshLoopJob: Job? = null

    @Volatile
    private var lastAdvertisedPaths: Set<String> = emptySet()

    fun initialize(context: Context) {
        ensureInitialized(context)
    }

    fun start(context: Context) {
        ensureInitialized(context)
        val appContext = context.applicationContext
        updateError(null)
        note("Starting host service", addToRecent = true)
        refreshDevices(context)
        requestPermissions(context)
        if (server != null) {
            mutableState.update { it.copy(serviceRunning = true) }
            startRefreshLoop(appContext)
            debug("Host service was already running")
            return
        }

        server = UsbBridgeServer(
            scope = scope,
            onStatus = { updateStatus(it) },
            onError = { updateError(it) },
            onClientChanged = { client ->
                mutableState.update { state ->
                    state.copy(connectedClient = client)
                }
            },
            devicesProvider = { state.value.devices.map { it.toSummary() } },
            openDevice = { id ->
                val candidate = candidatesById[id]
                    ?: throw IllegalArgumentException("Unknown USB candidate id $id")
                UsbDeviceCatalog.open(appContext, candidate)
            },
        ).also { it.start() }

        val currentPaths = state.value.devices.map(UsbCandidate::systemPath).toSet()
        lastAdvertisedPaths = currentPaths
        server?.onAvailableDevicesChanged(currentPaths)
        mutableState.update {
            it.copy(
                serviceRunning = true,
                status = listeningStatus(it.devices),
                serverIp = findLocalIpv4Address().orEmpty(),
            )
        }
        startRefreshLoop(appContext)
        note("Host is now listening for Linux clients", addToRecent = true)
    }

    fun stop() {
        note("Stopping host service", addToRecent = true)
        stopRefreshLoop()
        server?.stop()
        server = null
        lastAdvertisedPaths = emptySet()
        mutableState.update {
            it.copy(
                serviceRunning = false,
                connectedClient = null,
                status = "Stopped",
            )
        }
    }

    fun refreshDevices(context: Context) {
        ensureInitialized(context)
        runCatching {
            val devices = UsbDeviceCatalog.enumerate(context)
            candidatesById = devices.associateBy { it.id }
            val availablePaths = devices.map(UsbCandidate::systemPath).toSet()
            if (availablePaths != lastAdvertisedPaths) {
                lastAdvertisedPaths = availablePaths
                server?.onAvailableDevicesChanged(availablePaths)
            }
            val serverIp = findLocalIpv4Address().orEmpty()
            mutableState.update { current ->
                current.copy(
                    devices = devices,
                    serverIp = serverIp,
                    lastError = null,
                )
            }
            debug(
                "Enumerated ${devices.size} supported controller interface(s): " +
                    devices.joinToString { "${it.displayName} [${it.transportLabel}] ${it.systemPath}" },
            )
        }.onFailure { error ->
            mutableState.update {
                it.copy(
                    lastError = "USB refresh failed: ${error.message}",
                )
            }
            logError("USB refresh failed", error)
        }
    }

    fun requestPermissions(context: Context) {
        ensureInitialized(context)
        runCatching {
            UsbDeviceCatalog.requestPermissions(context, state.value.devices, ACTION_USB_PERMISSION)
            debug("Requested USB permissions for visible controller devices")
        }.onFailure { error ->
            updateError("USB permission request failed: ${error.message}")
        }
    }

    fun updateStatus(message: String) {
        val previous = state.value.status
        mutableState.update { it.copy(status = message) }
        if (previous != message) {
            note("Status changed: $message")
        }
    }

    fun updateError(message: String?) {
        mutableState.update { it.copy(lastError = message) }
        if (message != null) {
            logError(message)
        }
    }

    fun setVerboseLogging(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        prefs(context).edit().putBoolean(KEY_VERBOSE_LOGGING, enabled).apply()
        mutableState.update { it.copy(verboseLogging = enabled) }
        note(
            if (enabled) "Verbose logging enabled" else "Verbose logging disabled",
            addToRecent = true,
        )
    }

    fun setStartOnBoot(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()
        mutableState.update { it.copy(startOnBoot = enabled) }
        note(
            if (enabled) {
                "USBoss will start automatically after boot and app updates"
            } else {
                "USBoss will stay manual after boot"
            },
            addToRecent = true,
        )
    }

    fun shouldStartOnBoot(context: Context): Boolean {
        ensureInitialized(context)
        return prefs(context).getBoolean(KEY_START_ON_BOOT, false)
    }

    fun clearRecentEvents() {
        Log.i(TAG, "Cleared recent event log")
        mutableState.update { it.copy(recentEvents = emptyList()) }
    }

    fun debug(message: String) {
        if (!state.value.verboseLogging) {
            return
        }
        Log.d(TAG, message)
        appendRecentEvent("DEBUG", message)
    }

    fun note(message: String, addToRecent: Boolean = false) {
        Log.i(TAG, message)
        if (addToRecent || state.value.verboseLogging) {
            appendRecentEvent("INFO", message)
        }
    }

    fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
        val throwableSuffix = throwable?.let {
            val detail = it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName
            " ($detail)"
        }.orEmpty()
        appendRecentEvent("ERROR", message + throwableSuffix)
    }

    private fun listeningStatus(devices: List<UsbCandidate>): String {
        return if (devices.isEmpty()) {
            "Listening for Linux clients (no supported USB devices yet)"
        } else {
            "Listening for Linux clients"
        }
    }

    private fun findLocalIpv4Address(): String? {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        }.getOrElse {
            emptyList()
        }
        return interfaces
            .asSequence()
            .filter { !it.isLoopback && it.isUp }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) {
            return
        }
        synchronized(this) {
            if (initialized) {
                return
            }
            val sharedPrefs = prefs(context)
            val verboseLogging = sharedPrefs.getBoolean(KEY_VERBOSE_LOGGING, false)
            val startOnBoot = sharedPrefs.getBoolean(KEY_START_ON_BOOT, false)
            mutableState.update {
                it.copy(
                    verboseLogging = verboseLogging,
                    startOnBoot = startOnBoot,
                )
            }
            initialized = true
            note(
                if (verboseLogging) {
                    "Loaded settings with verbose logging enabled"
                } else {
                    "Loaded settings"
                },
                addToRecent = verboseLogging,
            )
        }
    }

    private fun startRefreshLoop(context: Context) {
        if (refreshLoopJob?.isActive == true) {
            return
        }
        refreshLoopJob = scope.launch {
            while (isActive && state.value.serviceRunning) {
                delay(REFRESH_LOOP_MS)
                if (!state.value.serviceRunning) {
                    break
                }
                runCatching {
                    refreshDevices(context)
                }.onFailure { error ->
                    logError("Background USB refresh failed", error)
                }
            }
        }
        debug("Started background USB refresh loop")
    }

    private fun stopRefreshLoop() {
        refreshLoopJob?.cancel()
        refreshLoopJob = null
        debug("Stopped background USB refresh loop")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun appendRecentEvent(level: String, message: String) {
        val timestamp = synchronized(timestampFormat) {
            timestampFormat.format(Date())
        }
        val line = "$timestamp [$level] $message"
        mutableState.update { current ->
            current.copy(recentEvents = (current.recentEvents + line).takeLast(MAX_RECENT_EVENTS))
        }
    }

    private fun UsbCandidate.toSummary(): Protocol.DeviceSummary {
        return Protocol.DeviceSummary(
            deviceId = id,
            transport = transport,
            vendorId = vendorId,
            productId = productId,
            interfaceNumber = interfaceNumber,
            interfaceClass = interfaceClass,
            interfaceSubclass = interfaceSubclass,
            interfaceProtocol = interfaceProtocol,
            inputPacketSize = inputPacketSize,
            outputPacketSize = outputPacketSize,
            hasInterruptOut = hasInterruptOut,
            manufacturer = manufacturer,
            product = product,
            serial = serial,
            systemPath = systemPath,
        )
    }
}
