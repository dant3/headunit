package ca.anodsplace.headunit.aap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.support.v4.app.NotificationCompat
import android.support.v4.content.LocalBroadcastManager
import android.widget.Toast
import ca.anodsplace.headunit.App
import ca.anodsplace.headunit.R
import ca.anodsplace.headunit.aap.protocol.messages.NightModeEvent
import ca.anodsplace.headunit.connection.AccessoryConnection
import ca.anodsplace.headunit.connection.SocketAccessoryConnection
import ca.anodsplace.headunit.connection.UsbAccessoryConnection
import ca.anodsplace.headunit.connection.UsbReceiver
import ca.anodsplace.headunit.location.GpsLocationService
import ca.anodsplace.headunit.utils.*
import info.anodsplace.headunit.contract.DisconnectIntent
import info.anodsplace.headunit.contract.LocationUpdateIntent

/**
 * @author algavris
 * *
 * @date 03/06/2016.
 */

class AapService : Service(), UsbReceiver.Listener, AccessoryConnection.Listener {

    private lateinit var uiModeManager: UiModeManager
    private var accessoryConnection: AccessoryConnection? = null
    private lateinit var usbReceiver: UsbReceiver
    private lateinit var nightModeReceiver: BroadcastReceiver

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        uiModeManager.nightMode = UiModeManager.MODE_NIGHT_AUTO

        usbReceiver = UsbReceiver(this)
        nightModeReceiver = NightModeReceiver(Settings(this), uiModeManager)

        val nightModeFilter = IntentFilter()
        nightModeFilter.addAction(Intent.ACTION_TIME_TICK)
        LocalBroadcastManager.getInstance(this).registerReceiver(nightModeReceiver, nightModeFilter)
        registerReceiver(nightModeReceiver, IntentFilters.locationUpdate)
        registerReceiver(nightModeReceiver, nightModeFilter)
        registerReceiver(usbReceiver, UsbReceiver.createFilter())
    }

    override fun onDestroy() {
        super.onDestroy()
        onDisconnect()
        unregisterReceiver(nightModeReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(nightModeReceiver)
        unregisterReceiver(usbReceiver)
        uiModeManager.disableCarMode(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        accessoryConnection = createConnection(intent, this)
        if (accessoryConnection == null) {
            AppLog.e("Cannot create connection " + intent)
            stopSelf()
            return START_NOT_STICKY
        }

        uiModeManager.enableCarMode(0)

        val noty = NotificationCompat.Builder(this, "default")
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setTicker("HeadUnit is running")
                .setWhen(System.currentTimeMillis())
                .setContentTitle("HeadUnit is running")
                .setContentText("...")
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, AapProjectionActivity.intent(this), PendingIntent.FLAG_UPDATE_CURRENT))
                .setPriority(Notification.PRIORITY_HIGH)
                .build()

        startService(GpsLocationService.intent(this))

        startForeground(1, noty)

        accessoryConnection!!.connect(this)

        return START_STICKY
    }

    override fun onConnectionResult(success: Boolean) {
        if (success) {
            reset()
            App.provide(this).transport.connectAndStart(accessoryConnection!!)
        } else {
            AppLog.e("Cannot connect to device")
            Toast.makeText(this, "Cannot connect to the device", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun onDisconnect() {
        sendBroadcast(DisconnectIntent())
        reset()
        accessoryConnection?.disconnect()
        accessoryConnection = null
    }

    private fun reset() {
        App.provide(this).resetTransport()
        App.provide(this).audioDecoder.stop()
        App.provide(this).videoDecoder.stop("AapService::reset")
    }

    override fun onUsbDetach(device: UsbDevice) {
        if (accessoryConnection is UsbAccessoryConnection) {
            if ((accessoryConnection as UsbAccessoryConnection).isDeviceRunning(device)) {
                stopSelf()
            }
        }
    }

    override fun onUsbAttach(device: UsbDevice) {

    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {

    }

    private class NightModeReceiver(private val settings: Settings, private val mUiModeManager: UiModeManager) : BroadcastReceiver() {
        private var nightMode = NightMode(settings, false)
        private var initialized = false
        private var lastValue = false

        override fun onReceive(context: Context, intent: Intent) {

            if (!nightMode.hasGPSLocation && intent.action == LocationUpdateIntent.action)
            {
                nightMode = NightMode(settings, true)
            }

            val isCurrent = nightMode.current
            if (!initialized || lastValue != isCurrent) {
                lastValue = isCurrent
                AppLog.i(nightMode.toString())
                initialized = App.provide(context).transport.send(NightModeEvent(isCurrent))
                if (initialized)
                {
                    mUiModeManager.nightMode = if (isCurrent) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
                }
            }
        }
    }

    companion object {
        private const val TYPE_USB = 1
        private const val TYPE_WIFI = 2
        private const val EXTRA_CONNECTION_TYPE = "extra_connection_type"
        private const val EXTRA_IP = "extra_ip"

        fun createIntent(device: UsbDevice, context: Context): Intent {
            val intent = Intent(context, AapService::class.java)
            intent.putExtra(UsbManager.EXTRA_DEVICE, device)
            intent.putExtra(EXTRA_CONNECTION_TYPE, TYPE_USB)
            return intent
        }

        fun createIntent(ip: String, context: Context): Intent {
            val intent = Intent(context, AapService::class.java)
            intent.putExtra(EXTRA_IP, ip)
            intent.putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI)
            return intent
        }

        private fun createConnection(intent: Intent?, context: Context): AccessoryConnection? {

            val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0

            if (connectionType == TYPE_USB) {
                val device = DeviceIntent(intent).device
                if (device == null) {
                    AppLog.e("No device in " + intent)
                    return null
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                return UsbAccessoryConnection(usbManager, device)
            } else if (connectionType == TYPE_WIFI) {
                val ip = intent?.getStringExtra(EXTRA_IP) ?: ""
                return SocketAccessoryConnection(ip)
            }

            return null
        }
    }
}
