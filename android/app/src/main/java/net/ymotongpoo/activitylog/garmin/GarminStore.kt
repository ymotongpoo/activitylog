package net.ymotongpoo.activitylog.garmin

import android.content.Context
import net.ymotongpoo.activitylog.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Durable inbox: ACK follows one commit of both display history and export outbox. */
class GarminStore(context: Context) {
    val prefs = context.getSharedPreferences("garmin_prototype", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    val status: String get() = prefs.getString("status", "Receiver stopped").orEmpty()
    val packetCount: Long get() = prefs.getLong("packets", 0)
    val lastReceivedAt: Long get() = prefs.getLong("receivedAt", 0)

    fun status(text: String) { prefs.edit().putString("status", text).apply() }

    fun history(): List<GarminProtocol.Receipt> = read("history")
    fun pending(): List<GarminProtocol.Receipt> = read("outbox")

    fun checkpoints(): Map<String, Long> {
        val json = JSONObject(prefs.getString("exportedMeasurements", "{}").orEmpty())
        return json.keys().asSequence().associateWith { json.getLong(it) }
    }

    /** Called only after the payload is durably in the shared OTLP spool. */
    fun exported(receipts: List<GarminProtocol.Receipt>, checkpoints: Map<String, Long>) = synchronized(LOCK) {
        val ids = receipts.map { it.deviceId to it.sample.capturedAt }.toSet()
        val remaining = pending().filterNot { (it.deviceId to it.sample.capturedAt) in ids }
        if (!prefs.edit().putString("outbox", json(remaining).toString())
                .putString("exportedMeasurements", JSONObject(checkpoints).toString()).commit()
        ) throw IOException("Could not checkpoint Garmin export")
    }

    private fun read(key: String): List<GarminProtocol.Receipt> {
        val rows = JSONArray(prefs.getString(key, "[]"))
        fun metric(row: JSONObject, spec: GarminMetric): GarminProtocol.Value? {
            val key = spec.key
            if (!row.has(key) || row.isNull(key)) return null
            val value: Number = if (spec.integer) row.getInt(key) else row.getDouble(key)
            return GarminProtocol.Value(value, row.getLong("${key}At"))
        }
        return (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            val values = GarminMetric.entries.mapNotNull { spec -> metric(row, spec)?.let { spec.key to it } }.toMap()
            GarminProtocol.Receipt(
                row.getLong("deviceId"), row.getString("deviceName"), row.getLong("receivedAt"),
                GarminProtocol.Sample(
                    row.getLong("capturedAt"), values["bodyBattery"], values["stress"],
                    values.filterKeys { key -> key != "bodyBattery" && key != "stress" },
                ),
            )
        }
    }

    /** ACK is sent only after commit returns true, including for an already persisted batch. */
    fun receive(deviceId: Long, name: String, samples: List<GarminProtocol.Sample>, receivedAt: Long) = synchronized(LOCK) {
        val incoming = samples.map { GarminProtocol.Receipt(deviceId, name, receivedAt, it) }
        val existing = history()
        val merged = GarminProtocol.merge(existing, incoming)
        val outbox = if (BuildConfig.GARMIN_EXPORT_ENABLED) {
            val seen = existing.map { it.deviceId to it.sample.capturedAt }.toSet()
            (pending() + incoming.filterNot { (it.deviceId to it.sample.capturedAt) in seen })
                .distinctBy { it.deviceId to it.sample.capturedAt }
        } else emptyList()
        // About seven days at five-minute intervals. Refuse ACK rather than
        // silently losing accepted data before it reaches the bounded OTLP spool.
        if (outbox.size > 2016) throw IOException("Garmin export queue full")
        if (!prefs.edit().putString("history", json(merged).toString()).putString("outbox", json(outbox).toString())
                .putLong("packets", packetCount + 1).putLong("receivedAt", receivedAt).commit()
        ) throw IOException("Could not save Garmin data; leaving the watch batch unacknowledged")
    }

    private fun json(receipts: List<GarminProtocol.Receipt>): JSONArray {
        val rows = JSONArray()
        for (receipt in receipts) {
            val sample = receipt.sample
            rows.put(JSONObject().apply {
                put("deviceId", receipt.deviceId)
                put("deviceName", receipt.deviceName)
                put("receivedAt", receipt.receivedAt)
                put("capturedAt", sample.capturedAt)
                for (spec in GarminMetric.entries) {
                    val reading = spec.reading(sample)
                    put(spec.key, reading?.value ?: JSONObject.NULL)
                    put("${spec.key}At", reading?.time ?: JSONObject.NULL)
                }
            })
        }
        return rows
    }

    companion object { private val LOCK = Any() }
}
