package net.ymotongpoo.activitylog.collect

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import net.ymotongpoo.activitylog.metrics.MetricNames

data class NetworkBytes(val received: Long, val sent: Long) {
    val total: Long get() = received + sent

    operator fun plus(other: NetworkBytes) = NetworkBytes(
        received = received + other.received,
        sent = sent + other.sent,
    )
}

data class NetworkUsageSnapshot(
    val device: NetworkBytes,
    val byUid: Map<Int, NetworkBytes>,
)

enum class NetworkTransport(val networkType: Int, val metricName: String, val label: String) {
    MOBILE(ConnectivityManager.TYPE_MOBILE, MetricNames.MOBILE_DATA_USAGE, "Mobile"),
    WIFI(ConnectivityManager.TYPE_WIFI, MetricNames.WIFI_DATA_USAGE, "Wi-Fi"),
}

/** Reads network traffic for a time window from Android's network usage history. */
class NetworkUsageCollector(context: Context, private val transport: NetworkTransport) {
    private val manager = context.getSystemService(NetworkStatsManager::class.java)

    fun query(startMs: Long, endMs: Long): NetworkUsageSnapshot {
        val statsManager = checkNotNull(manager) { "NetworkStatsManager is unavailable" }
        require(startMs <= endMs)

        // A null subscriber ID includes all networks of this transport. On Android
        // 10+ this also avoids reading the restricted mobile subscriber ID.
        val device = checkNotNull(
            statsManager.querySummaryForDevice(
                transport.networkType,
                null,
                startMs,
                endMs,
            ),
        ) { "Android returned no device ${transport.label} usage statistics" }

        val byUid = mutableMapOf<Int, NetworkBytes>()
        val stats = checkNotNull(
            statsManager.querySummary(
                transport.networkType,
                null,
                startMs,
                endMs,
            ),
        ) { "Android returned no per-UID ${transport.label} usage statistics" }
        try {
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                if (!stats.getNextBucket(bucket)) break
                val bytes = NetworkBytes(bucket.rxBytes, bucket.txBytes)
                byUid[bucket.uid] = (byUid[bucket.uid] ?: NetworkBytes(0, 0)) + bytes
            }
        } finally {
            stats.close()
        }

        return NetworkUsageSnapshot(
            device = NetworkBytes(device.rxBytes, device.txBytes),
            byUid = byUid,
        )
    }
}
