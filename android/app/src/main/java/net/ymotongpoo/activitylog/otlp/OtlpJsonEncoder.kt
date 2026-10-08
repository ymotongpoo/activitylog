package net.ymotongpoo.activitylog.otlp

/**
 * Encodes telemetry into the OTLP/HTTP JSON format (proto3 JSON mapping):
 * trace and span IDs are lowercase hex, 64-bit integers are decimal strings and
 * enums are integers.
 */
object OtlpJsonEncoder {
    /** SPAN_KIND_INTERNAL */
    const val SPAN_KIND_INTERNAL = 1L

    /** AGGREGATION_TEMPORALITY_CUMULATIVE */
    const val AGGREGATION_TEMPORALITY_CUMULATIVE = 2L

    fun encodeTraces(resource: List<Attribute>, scope: Scope, spans: List<SpanData>): String {
        val w = JsonWriter()
        w.beginObject().name("resourceSpans").beginArray().beginObject()
        writeResource(w, resource)
        w.name("scopeSpans").beginArray().beginObject()
        writeScope(w, scope)
        w.name("spans").beginArray()
        for (s in spans) {
            w.beginObject()
            w.field("traceId", s.traceId)
            w.field("spanId", s.spanId)
            if (!s.parentSpanId.isNullOrEmpty()) w.field("parentSpanId", s.parentSpanId)
            w.field("name", s.name)
            w.field("kind", SPAN_KIND_INTERNAL)
            w.field("startTimeUnixNano", s.startTimeUnixNano.toString())
            w.field("endTimeUnixNano", s.endTimeUnixNano.toString())
            writeAttributes(w, s.attributes)
            if (s.links.isNotEmpty()) {
                w.name("links").beginArray()
                for (l in s.links) {
                    w.beginObject().field("traceId", l.traceId).field("spanId", l.spanId).endObject()
                }
                w.endArray()
            }
            w.endObject()
        }
        w.endArray()
        w.endObject().endArray()
        w.endObject().endArray().endObject()
        return w.toString()
    }

    fun encodeLogs(resource: List<Attribute>, scope: Scope, logs: List<LogData>): String {
        val w = JsonWriter()
        w.beginObject().name("resourceLogs").beginArray().beginObject()
        writeResource(w, resource)
        w.name("scopeLogs").beginArray().beginObject()
        writeScope(w, scope)
        w.name("logRecords").beginArray()
        for (l in logs) {
            w.beginObject()
            w.field("timeUnixNano", l.timeUnixNano.toString())
            w.field("observedTimeUnixNano", l.observedTimeUnixNano.toString())
            w.field("severityNumber", l.severity.number.toLong())
            w.field("severityText", l.severity.text)
            w.name("body").beginObject().field("stringValue", l.body).endObject()
            writeAttributes(w, l.attributes)
            if (!l.traceId.isNullOrEmpty() && !l.spanId.isNullOrEmpty()) {
                w.field("traceId", l.traceId)
                w.field("spanId", l.spanId)
            }
            w.endObject()
        }
        w.endArray()
        w.endObject().endArray()
        w.endObject().endArray().endObject()
        return w.toString()
    }

    fun encodeMetrics(resource: List<Attribute>, scope: Scope, metrics: List<SumMetric>): String {
        val w = JsonWriter()
        w.beginObject().name("resourceMetrics").beginArray().beginObject()
        writeResource(w, resource)
        w.name("scopeMetrics").beginArray().beginObject()
        writeScope(w, scope)
        w.name("metrics").beginArray()
        for (m in metrics) {
            w.beginObject()
            w.field("name", m.name)
            w.field("description", m.description)
            w.field("unit", m.unit)
            w.name("sum").beginObject()
            w.field("aggregationTemporality", AGGREGATION_TEMPORALITY_CUMULATIVE)
            w.field("isMonotonic", true)
            w.name("dataPoints").beginArray()
            for (p in m.points) {
                w.beginObject()
                writeAttributes(w, p.attributes)
                w.field("startTimeUnixNano", p.startTimeUnixNano.toString())
                w.field("timeUnixNano", p.timeUnixNano.toString())
                if (m.integer) {
                    w.field("asInt", p.value.toLong().toString())
                } else {
                    w.field("asDouble", p.value)
                }
                w.endObject()
            }
            w.endArray()
            w.endObject()
            w.endObject()
        }
        w.endArray()
        w.endObject().endArray()
        w.endObject().endArray().endObject()
        return w.toString()
    }

    private fun writeResource(w: JsonWriter, resource: List<Attribute>) {
        w.name("resource").beginObject()
        writeAttributes(w, resource)
        w.endObject()
    }

    private fun writeScope(w: JsonWriter, scope: Scope) {
        w.name("scope").beginObject().field("name", scope.name).field("version", scope.version).endObject()
    }

    private fun writeAttributes(w: JsonWriter, attributes: List<Attribute>) {
        w.name("attributes").beginArray()
        for (a in attributes) {
            w.beginObject().field("key", a.key).name("value").beginObject()
            when (val v = a.value) {
                is AttributeValue.Str -> w.field("stringValue", v.value)
                is AttributeValue.IntVal -> w.field("intValue", v.value.toString())
                is AttributeValue.BoolVal -> w.field("boolValue", v.value)
                is AttributeValue.DoubleVal -> w.field("doubleValue", v.value)
            }
            w.endObject().endObject()
        }
        w.endArray()
    }
}
