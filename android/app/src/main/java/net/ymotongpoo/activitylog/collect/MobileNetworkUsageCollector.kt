package net.ymotongpoo.activitylog.collect

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager

data class NetworkBytes(val received: Long, val sent: Long) {
    val total: Long get() = received + sent

    operator fun plus(other: NetworkBytes) = NetworkBytes(
        received = received + other.received,
        sent = sent + other.sent,
    )
}

data class MobileNetworkUsageSnapshot(
    val device: NetworkBytes,
    val byUid: Map<Int, NetworkBytes>,
)

/** Reads mobile traffic for a time window from Android's network usage history. */
class MobileNetworkUsageCollector(context: Context) {
    private val manager = context.getSystemService(NetworkStatsManager::class.java)

    fun query(startMs: Long, endMs: Long): MobileNetworkUsageSnapshot {
        val statsManager = checkNotNull(manager) { "NetworkStatsManager is unavailable" }
        require(startMs <= endMs)

        // A null subscriber ID asks Android for usage across mobile networks and
        // avoids reading the restricted subscriber ID on Android 10 and later.
        val device = checkNotNull(
            statsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                null,
                startMs,
                endMs,
            ),
        ) { "Android returned no device mobile usage statistics" }

        val byUid = mutableMapOf<Int, NetworkBytes>()
        val stats = checkNotNull(
            statsManager.querySummary(
                ConnectivityManager.TYPE_MOBILE,
                null,
                startMs,
                endMs,
            ),
        ) { "Android returned no per-UID mobile usage statistics" }
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

        return MobileNetworkUsageSnapshot(
            device = NetworkBytes(device.rxBytes, device.txBytes),
            byUid = byUid,
        )
    }
}
