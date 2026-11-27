package app.qitou.bridge

import android.content.Context

object LaunchConnectionPolicy {
    fun shouldAutoConnect(
        enabled: Boolean,
        hasSavedVehicle: Boolean,
        serviceActive: Boolean,
    ): Boolean = enabled && hasSavedVehicle && !serviceActive
}

object AutoConnectStore {
    private const val PREFS = "qitou_connection"
    private const val KEY_AUTO_CONNECT_SAVED_VEHICLE = "auto_connect_saved_vehicle"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONNECT_SAVED_VEHICLE, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONNECT_SAVED_VEHICLE, enabled)
            .apply()
    }
}
