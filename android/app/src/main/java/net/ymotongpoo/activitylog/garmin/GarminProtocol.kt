package net.ymotongpoo.activitylog.garmin

/** Wire format shared with garmin/source/HealthProtocol.mc. Times are Unix seconds. */
object GarminProtocol {
    const val APP_ID = "5d6df629ae944b64a5806a8dc04bb170"
    const val MAX_BATCH = 12
    const val HISTORY_LIMIT = 288

    data class Value(val value: Number, val time: Long)
    data class Sample(
        val capturedAt: Long,
        val bodyBattery: Value?,
        val stress: Value?,
        val additional: Map<String, Value> = emptyMap(),
    )
    data class Receipt(val deviceId: Long, val deviceName: String, val receivedAt: Long, val sample: Sample)

    fun decode(message: Any?): List<Sample> {
        // The Mobile SDK wraps the transmitted dictionary in a one-element list.
        val envelope = when (message) {
            is Map<*, *> -> message
            is List<*> -> message.singleOrNull() as? Map<*, *>
            else -> null
        }
        requireNotNull(envelope) { "Expected a health message" }
        require(integer(envelope["schema"]) == 1L && envelope["type"] == "health") {
            "Unsupported health protocol"
        }
        val rows = envelope["samples"] as? List<*> ?: error("Missing samples")
        require(rows.size in 1..MAX_BATCH) { "Invalid batch size" }
        val samples = rows.map { row ->
            val fields = row as? Map<*, *> ?: error("Invalid sample")
            val capturedAt = timestamp(fields["capturedAt"])
            fun metric(spec: GarminMetric): Value? {
                val key = spec.key
                val value = fields[key]
                val time = fields["${key}At"]
                if (value == null && time == null) return null
                require(value is Number) { "Expected a number for $key" }
                val number = value.toDouble()
                require(number.isFinite() && number >= spec.minimum && number <= spec.maximum) { "Invalid $key value" }
                val decoded: Number = if (spec.integer) integer(value).toInt() else number
                val measuredAt = timestamp(time)
                require(measuredAt <= capturedAt + 60) { "Measurement is after collection" }
                return Value(decoded, measuredAt)
            }
            val values = GarminMetric.entries.mapNotNull { spec -> metric(spec)?.let { spec.key to it } }.toMap()
            Sample(
                capturedAt, values["bodyBattery"], values["stress"],
                values.filterKeys { it != "bodyBattery" && it != "stress" },
            )
        }
        require(samples.zipWithNext().all { (a, b) -> a.capturedAt < b.capturedAt }) {
            "Samples must have unique, increasing collection times"
        }
        return samples
    }

    fun acknowledgement(samples: List<Sample>): Map<String, Any> =
        mapOf("schema" to 1, "ack" to samples.last().capturedAt)

    /** Retransmission does not replace the original receipt time. Devices have separate identities. */
    fun merge(existing: List<Receipt>, incoming: List<Receipt>): List<Receipt> =
        (existing + incoming).distinctBy { it.deviceId to it.sample.capturedAt }
            .sortedBy { it.sample.capturedAt }.takeLast(HISTORY_LIMIT)

    private fun timestamp(value: Any?): Long = integer(value).also {
        require(it in 946684800L..4102444800L) { "Invalid Unix timestamp" }
    }

    private fun integer(value: Any?): Long {
        require(value is Number) { "Expected a number" }
        val result = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == result.toDouble()) { "Expected an integer" }
        return result
    }
}
