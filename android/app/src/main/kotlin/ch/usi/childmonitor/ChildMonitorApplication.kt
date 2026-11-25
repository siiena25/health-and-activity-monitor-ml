package ch.usi.childmonitor

import android.app.Application
import android.os.SystemClock
import ch.usi.childmonitor.alerts.AlertNotifier
import ch.usi.childmonitor.alerts.AlertSettingsStore
import ch.usi.childmonitor.alerts.AndroidAlertNotifier
import ch.usi.childmonitor.alerts.PreferencesAlertSettingsStore
import ch.usi.childmonitor.ble.AndroidMonitorLink
import ch.usi.childmonitor.ble.BluetoothStateObserver
import ch.usi.childmonitor.ble.MockMonitorLink
import ch.usi.childmonitor.ble.MonitorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: () -> Long = SystemClock::elapsedRealtime
    val realLink: MonitorLink = AndroidMonitorLink(application, clock)
    val mockLink: MonitorLink = MockMonitorLink(scope, clock)
    val bluetoothState = BluetoothStateObserver(application)
    val alertSettings: AlertSettingsStore = PreferencesAlertSettingsStore(application)
    val alertNotifier: AlertNotifier = AndroidAlertNotifier(application)
}

class ChildMonitorApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
