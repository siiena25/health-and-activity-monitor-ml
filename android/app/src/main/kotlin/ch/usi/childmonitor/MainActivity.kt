package ch.usi.childmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.usi.childmonitor.ui.ChildMonitorApp
import ch.usi.childmonitor.ui.MonitorViewModel
import ch.usi.childmonitor.ui.theme.ChildMonitorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ChildMonitorApplication).container
        setContent {
            ChildMonitorTheme {
                val viewModel: MonitorViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            MonitorViewModel(
                                realLink = container.realLink,
                                mockLink = container.mockLink,
                                bluetoothEnabled = container.bluetoothState.enabled,
                                clock = container.clock,
                                alertSettings = container.alertSettings,
                                notifier = container.alertNotifier,
                            )
                        }
                    },
                )
                ChildMonitorApp(viewModel)
            }
        }
    }
}
