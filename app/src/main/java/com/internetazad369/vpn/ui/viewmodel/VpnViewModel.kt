package com.internetazad369.vpn.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.internetazad369.vpn.core.HostResolver
import com.internetazad369.vpn.core.SingBoxConfigGenerator
import com.internetazad369.vpn.core.UnderlyingNetworks
import com.internetazad369.vpn.data.model.ConnectionState
import com.internetazad369.vpn.data.model.TrafficStats
import com.internetazad369.vpn.data.repository.SubscriptionRepository
import com.internetazad369.vpn.service.AzadVpnService
import com.internetazad369.vpn.service.VpnServiceStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VpnViewModel"
    }

    private val repository = SubscriptionRepository(application.applicationContext)

    val connectionState: StateFlow<ConnectionState> = VpnServiceStateHolder.connectionState
    val trafficStats: StateFlow<TrafficStats> = VpnServiceStateHolder.trafficStats

    private val _connectionDurationSeconds = MutableStateFlow(0L)
    val connectionDurationSeconds: StateFlow<Long> = _connectionDurationSeconds.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var timerJob: Job? = null
    private var pendingConnectJob: Job? = null

    init {
        // Monitor state transitions to start/stop duration timer
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        startDurationTimer(state.connectedTimestamp)
                        _errorMessage.value = null
                    }
                    is ConnectionState.Error -> {
                        stopDurationTimer()
                        _errorMessage.value = state.errorMessage
                    }
                    is ConnectionState.Disconnected -> {
                        stopDurationTimer()
                    }
                    else -> {}
                }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Primary action triggered by the single large connection button.
     */
    fun onButtonClick(vpnPermissionLauncher: ActivityResultLauncher<Intent>) {
        when (connectionState.value) {
            is ConnectionState.Connected -> {
                disconnectVpn()
            }
            is ConnectionState.Connecting,
            is ConnectionState.Fetching,
            is ConnectionState.Validating,
            is ConnectionState.WaitingForPermission -> {
                // Ignore multiple taps while busy
                Log.d(TAG, "Operation already in progress")
            }
            is ConnectionState.Disconnected,
            is ConnectionState.Error -> {
                requestConnection(vpnPermissionLauncher)
            }
            else -> {}
        }
    }

    private fun requestConnection(vpnPermissionLauncher: ActivityResultLauncher<Intent>) {
        val context = getApplication<Application>()
        _errorMessage.value = null

        // Step 1: Check VpnService system authorization
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            VpnServiceStateHolder.updateState(ConnectionState.WaitingForPermission)
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            // Already authorized, start connection pipeline immediately
            startConnectionPipeline()
        }
    }

    /**
     * Callback when user answers the system VPN permission dialog.
     */
    fun onVpnPermissionResult(isGranted: Boolean) {
        if (isGranted) {
            startConnectionPipeline()
        } else {
            VpnServiceStateHolder.updateState(ConnectionState.Disconnected)
            _errorMessage.value = "مجوز اتصال VPN توسط شما تأیید نشد. برای برقراری ارتباط، لطفاً مجوز را بپذیرید."
        }
    }

    private fun startConnectionPipeline() {
        pendingConnectJob?.cancel()
        pendingConnectJob = viewModelScope.launch {
            try {
                // Step 2: Fetch and validate subscription
                VpnServiceStateHolder.updateState(ConnectionState.Fetching("در حال دریافت تنظیمات..."))
                val validConfigs = repository.getValidatedConfigs(forceRefresh = false)

                if (validConfigs.isEmpty()) {
                    VpnServiceStateHolder.updateState(
                        ConnectionState.Error("هیچ سرور معتبری در اشتراک یافت نشد")
                    )
                    return@launch
                }

                // Step 3: Validate and generate sing-box engine config
                VpnServiceStateHolder.updateState(ConnectionState.Validating(validConfigs.size))
                delay(300) // Brief UI stage progression

                VpnServiceStateHolder.updateState(ConnectionState.Connecting("راه‌اندازی هسته sing-box..."))

                // Resolve proxy hostnames NOW, while the real network is still the default route and
                // before our own tunnel exists. The core then dials plain IPs and needs no DNS to bootstrap.
                val context = getApplication<Application>()
                val usableConfigs = SingBoxConfigGenerator.selectUsableProxies(validConfigs)
                val resolvedHosts = HostResolver.resolveAll(context, usableConfigs.map { it.server })
                val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val systemDns = connectivity?.let { UnderlyingNetworks.systemDnsServers(it) }.orEmpty()
                Log.i(TAG, "nodes=${usableConfigs.size} preResolved=${resolvedHosts.size} systemDns=$systemDns")

                val logFile = File(context.filesDir, "singbox.log").also { it.delete() }
                val configJson = SingBoxConfigGenerator.generate(
                    proxies = usableConfigs,
                    logFile = logFile,
                    resolvedHosts = resolvedHosts,
                    systemDns = systemDns
                )

                // Step 4: Start Android VPN foreground service
                val serviceIntent = Intent(context, AzadVpnService::class.java).apply {
                    action = AzadVpnService.ACTION_START
                    putExtra(AzadVpnService.EXTRA_CONFIG_JSON, configJson)
                    putExtra(AzadVpnService.EXTRA_SERVER_NAME, repository.getProfileMetadata().title)
                }

                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Connection pipeline failed: ${e.message}", e)
                VpnServiceStateHolder.updateState(
                    ConnectionState.Error(e.message ?: "خطا در برقراری ارتباط")
                )
            }
        }
    }

    fun disconnectVpn() {
        val context = getApplication<Application>()
        val serviceIntent = Intent(context, AzadVpnService::class.java).apply {
            action = AzadVpnService.ACTION_STOP
        }
        context.startService(serviceIntent)
    }

    private fun startDurationTimer(startTimestamp: Long) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startTimestamp) / 1000
                _connectionDurationSeconds.value = if (elapsed > 0) elapsed else 0L
                delay(1000)
            }
        }
    }

    private fun stopDurationTimer() {
        timerJob?.cancel()
        timerJob = null
        _connectionDurationSeconds.value = 0L
    }

    override fun onCleared() {
        timerJob?.cancel()
        pendingConnectJob?.cancel()
        super.onCleared()
    }
}
