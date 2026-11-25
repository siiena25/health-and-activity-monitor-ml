package ch.usi.childmonitor.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class BluetoothStateObserver(context: Context) {

    private val appContext = context.applicationContext

    val enabled: Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(isEnabled())
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(isEnabled())
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun isEnabled(): Boolean =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
}
