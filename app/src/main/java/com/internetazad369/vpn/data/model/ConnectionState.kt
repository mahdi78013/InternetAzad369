package com.internetazad369.vpn.data.model

/**
 * Representation of the one-button VPN connection states.
 */
sealed class ConnectionState {
    abstract val persianStatus: String
    abstract val buttonLabel: String
    abstract val isActionEnabled: Boolean

    /**
     * Initial/idle state: Ready to connect.
     */
    data object Disconnected : ConnectionState() {
        override val persianStatus: String = "آماده اتصال"
        override val buttonLabel: String = "اتصال"
        override val isActionEnabled: Boolean = true
    }

    /**
     * Waiting for system VPN authorization dialog confirmation.
     */
    data object WaitingForPermission : ConnectionState() {
        override val persianStatus: String = "در انتظار تأیید مجوز سیستم"
        override val buttonLabel: String = "در انتظار مجوز..."
        override val isActionEnabled: Boolean = false
    }

    /**
     * Fetching subscription profile from remote source.
     */
    data class Fetching(val message: String = "در حال دریافت تنظیمات...") : ConnectionState() {
        override val persianStatus: String = "در حال دریافت تنظیمات"
        override val buttonLabel: String = "در حال دریافت..."
        override val isActionEnabled: Boolean = false
    }

    /**
     * Validating and parsing extracted server configurations.
     */
    data class Validating(val count: Int = 0) : ConnectionState() {
        override val persianStatus: String = "در حال اعتبارسنجی سرورها"
        override val buttonLabel: String = "اعتبارسنجی..."
        override val isActionEnabled: Boolean = false
    }

    /**
     * Tunnel initialization and sing-box engine startup.
     */
    data class Connecting(val stage: String = "در حال برقراری تونل امن...") : ConnectionState() {
        override val persianStatus: String = "در حال اتصال"
        override val buttonLabel: String = "در حال اتصال..."
        override val isActionEnabled: Boolean = false
    }

    /**
     * Successfully connected: Real VPN tunnel is established, status bar VPN key icon visible.
     */
    data class Connected(
        val serverName: String = "اتصال هوشمند (Auto)",
        val connectedTimestamp: Long = System.currentTimeMillis()
    ) : ConnectionState() {
        override val persianStatus: String = "متصل"
        override val buttonLabel: String = "قطع اتصال"
        override val isActionEnabled: Boolean = true
    }

    /**
     * Disconnecting and releasing resources.
     */
    data object Disconnecting : ConnectionState() {
        override val persianStatus: String = "در حال قطع اتصال"
        override val buttonLabel: String = "در حال قطع..."
        override val isActionEnabled: Boolean = false
    }

    /**
     * Reconnecting after network handover or transient interruption.
     */
    data class Reconnecting(val reason: String = "تلاش مجدد اتصال") : ConnectionState() {
        override val persianStatus: String = "در حال اتصال مجدد"
        override val buttonLabel: String = "در حال اتصال..."
        override val isActionEnabled: Boolean = false
    }

    /**
     * Error state: Informative Persian error message and retry button.
     */
    data class Error(val errorMessage: String) : ConnectionState() {
        override val persianStatus: String = "اتصال برقرار نشد"
        override val buttonLabel: String = "تلاش مجدد"
        override val isActionEnabled: Boolean = true
    }
}
