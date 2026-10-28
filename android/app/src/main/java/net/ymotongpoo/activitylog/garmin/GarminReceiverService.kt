package net.ymotongpoo.activitylog.garmin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import net.ymotongpoo.activitylog.R
import net.ymotongpoo.activitylog.BuildConfig
import net.ymotongpoo.activitylog.work.Scheduler
import net.ymotongpoo.activitylog.settings.StatusStore
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Keeps the Mobile SDK subscription alive with the screen off. Garmin Connect owns
 * the BLE connection; this service does not pair with or take over the watch.
 */
class GarminReceiverService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: GarminStore
    private var sdk: ConnectIQ? = null
    private var ready = false
    private var destroyed = false
    private var generation = 0
    private var initializingAt = 0L
    private val devices = mutableMapOf<Long, IQDevice>()
    private val subscribed = mutableSetOf<Long>()
    private val checking = mutableMapOf<Long, Long>()
    private val refresh = object : Runnable {
        override fun run() {
            if (destroyed) return
            try {
                if (sdk != null && !ready && SystemClock.elapsedRealtime() - initializingAt >= 30_000) releaseSdk()
                if (sdk == null) initialize() else if (ready) discover()
            } catch (e: Exception) {
                store.status("Garmin connection failed: ${e.javaClass.simpleName}. Retrying…")
                releaseSdk()
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = GarminStore(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** adb diagnostics remain available while the phone is locked; no export credentials. */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>) {
        writer.println("Garmin receiver: enabled=${store.enabled}, running=$running")
        writer.println("Status: ${store.status}")
        writer.println("Packets=${store.packetCount}, lastReceivedAt=${store.lastReceivedAt}")
        writer.println("Pending export=${store.pending().size}, checkpoints=${store.checkpoints()}")
        store.history().takeLast(4).forEach { writer.println(it) }
        if (BuildConfig.GARMIN_EXPORT_ENABLED) writer.println("OTLP: ${StatusStore(this).lastSendResult}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) store.enabled = false
        if (!store.enabled || !hasConnectionPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        running = true
        if (BuildConfig.GARMIN_EXPORT_ENABLED && store.pending().isNotEmpty()) Scheduler.enqueueFlush(this)
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        return START_STICKY
    }

    private fun initialize() {
        val current = ++generation
        initializingAt = SystemClock.elapsedRealtime()
        store.status("Connecting to Garmin Connect…")
        val connection = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS)
        sdk = connection
        connection.initialize(this, false, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                handler.post {
                    if (!destroyed && current == generation) {
                        ready = true
                        discover()
                    }
                }
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                handler.post {
                    if (!destroyed && current == generation) {
                        store.status("Garmin Connect: $status. Install/open Garmin Connect, then retry.")
                        releaseSdk()
                    }
                }
            }

            override fun onSdkShutDown() {
                handler.post {
                    if (!destroyed && current == generation) {
                        store.status("Garmin Connect disconnected; retrying…")
                        releaseSdk()
                    }
                }
            }
        })
    }

    private fun discover() {
        val connection = sdk ?: return
        try {
            val known = connection.knownDevices.orEmpty()
            if (known.isEmpty()) store.status(getString(R.string.garmin_no_watches))
            for (device in known) {
                val id = device.deviceIdentifier
                if (devices.put(id, device) == null) {
                    connection.registerForDeviceEvents(device) { changed, status ->
                        handler.post {
                            if (destroyed || !ready) return@post
                            if (status == IQDevice.IQDeviceStatus.CONNECTED) subscribe(devices[changed.deviceIdentifier] ?: changed)
                            else {
                                subscribed.remove(changed.deviceIdentifier)
                                checking.remove(changed.deviceIdentifier)
                                store.status("${deviceName(changed)}: disconnected; waiting for reconnection")
                            }
                        }
                    }
                }
                if (connection.getDeviceStatus(device) == IQDevice.IQDeviceStatus.CONNECTED) subscribe(device)
                else {
                    subscribed.remove(id)
                    checking.remove(id)
                    store.status("${device.friendlyName}: not connected in Garmin Connect")
                }
            }
        } catch (e: Exception) {
            store.status("Garmin discovery failed: ${e.javaClass.simpleName}. Retrying…")
            releaseSdk()
        }
    }

    private fun subscribe(device: IQDevice) {
        val connection = sdk ?: return
        val id = device.deviceIdentifier
        val now = SystemClock.elapsedRealtime()
        if (id in subscribed || checking[id]?.let { now - it < 30_000 } == true) return
        checking[id] = now
        val current = generation
        try {
            connection.getApplicationInfo(GarminProtocol.APP_ID, device, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    handler.post subscribeResult@ {
                        checking.remove(id)
                        if (destroyed || !ready || current != generation) return@subscribeResult
                        if (app.status != IQApp.IQAppStatus.INSTALLED) {
                            store.status("Activitylog Health is not installed on ${device.friendlyName}.")
                            return@subscribeResult
                        }
                        try {
                            connection.registerForAppEvents(device, app) { source, _, data, status ->
                                handler.post messageResult@ {
                                    if (destroyed || current != generation) return@messageResult
                                    if (status == ConnectIQ.IQMessageStatus.SUCCESS) receive(source, app, data)
                                    else store.status("Garmin message error: $status")
                                }
                            }
                            subscribed.add(id)
                            store.status("Listening to ${device.friendlyName}; waiting for a five-minute sample")
                        } catch (e: Exception) {
                            store.status("Could not subscribe: ${e.javaClass.simpleName}. Retrying…")
                        }
                    }
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    handler.post {
                        checking.remove(id)
                        if (!destroyed && current == generation) {
                            store.status("Install Activitylog Health on ${device.friendlyName} and open it once.")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            checking.remove(id)
            store.status("Watch app unavailable: ${e.javaClass.simpleName}. Retrying…")
        }
    }

    private fun receive(device: IQDevice, app: IQApp, message: List<Any>) {
        try {
            val samples = GarminProtocol.decode(message)
            store.receive(device.deviceIdentifier, deviceName(device), samples, System.currentTimeMillis() / 1000)
            if (BuildConfig.GARMIN_EXPORT_ENABLED) Scheduler.enqueueFlush(this)
            store.status("Received ${samples.size} sample(s) from ${deviceName(device)}")
            val current = generation
            sdk?.sendMessage(device, app, GarminProtocol.acknowledgement(samples)) { _, _, status ->
                handler.post {
                    if (!destroyed && current == generation && status != ConnectIQ.IQMessageStatus.SUCCESS) {
                        store.status("Saved locally; ACK failed ($status). A duplicate retry is safe.")
                    }
                }
            }
        } catch (e: Exception) {
            store.status("Message not acknowledged: ${e.message?.take(180) ?: e.javaClass.simpleName}")
        }
    }

    private fun deviceName(device: IQDevice): String =
        device.friendlyName.takeIf { !it.isNullOrBlank() }
            ?: devices[device.deviceIdentifier]?.friendlyName?.takeIf { it.isNotBlank() }
            ?: "Garmin ${device.deviceIdentifier}"

    private fun releaseSdk() {
        ++generation
        ready = false
        checking.clear()
        subscribed.clear()
        devices.clear()
        val old = sdk
        sdk = null
        runCatching { old?.unregisterAllForEvents() }
        runCatching { old?.shutdown(this) }
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        handler.removeCallbacksAndMessages(null)
        releaseSdk()
        store.status("Receiver stopped")
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.garmin_title), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, GarminActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, GarminReceiverService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.garmin_notification))
            .setContentText(getString(R.string.garmin_notification_detail))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.garmin_stop), stop).build()).build()
    }

    companion object {
        private const val CHANNEL = "garmin_health"
        private const val NOTIFICATION_ID = 2
        private const val STOP = "net.ymotongpoo.activitylog.garmin.STOP"
        @Volatile var running = false
            private set

        /** Called from visible activities, boot and package updates. Opt-in survives restarts. */
        fun sync(context: Context) {
            val store = GarminStore(context)
            val intent = Intent(context, GarminReceiverService::class.java)
            if (!store.enabled || !hasConnectionPermission(context)) {
                context.stopService(intent)
                return
            }
            if (running) return
            try {
                context.startForegroundService(intent)
            } catch (e: RuntimeException) {
                store.status("Receiver could not restart (${e.javaClass.simpleName}); open Garmin health to resume")
            }
        }

        fun hasConnectionPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }
}
