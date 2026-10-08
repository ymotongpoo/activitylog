package net.ymotongpoo.activitylog.otlp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtlpJsonEncoderTest {
    private val resource = listOf(
        attr("service.name", "activitylog-android"),
        attr("service.namespace", "activitylog"),
    )
    private val scope = Scope("activitylog-android", "0.1.0")
    private val traceId = "0123456789abcdef0123456789abcdef"
    private val spanId = "0123456789abcdef"

    @Test
    fun tracesShape() {
        val json = OtlpJsonEncoder.encodeTraces(
            resource,
            scope,
            listOf(
                SpanData(
                    traceId = traceId,
                    spanId = spanId,
                    parentSpanId = null,
                    name = "active",
                    startTimeUnixNano = 1_760_000_000_000_000_000,
                    endTimeUnixNano = 1_760_000_060_000_000_000,
                    attributes = listOf(
                        attr("activity.state", "active"),
                        attr("activity.app.switches", 3),
                        attr("activity.browser.incognito", false),
                        attr("ratio", 0.5),
                    ),
                    links = listOf(SpanLink("f".repeat(32), "e".repeat(16))),
                ),
                SpanData(traceId, "1".repeat(16), spanId, "Chrome \"beta\"\n", 1, 2, emptyList()),
            ),
        )
        val root = JSONObject(json)
        val rs = root.getJSONArray("resourceSpans").getJSONObject(0)
        val resAttrs = rs.getJSONObject("resource").getJSONArray("attributes")
        assertEquals("service.name", resAttrs.getJSONObject(0).getString("key"))
        assertEquals("activitylog-android", resAttrs.getJSONObject(0).getJSONObject("value").getString("stringValue"))

        val ss = rs.getJSONArray("scopeSpans").getJSONObject(0)
        assertEquals("activitylog-android", ss.getJSONObject("scope").getString("name"))
        assertEquals("0.1.0", ss.getJSONObject("scope").getString("version"))
        val spans = ss.getJSONArray("spans")
        val s0 = spans.getJSONObject(0)
        assertEquals(traceId, s0.getString("traceId"))
        assertEquals(spanId, s0.getString("spanId"))
        assertFalse(s0.has("parentSpanId"))
        assertEquals(1, s0.getInt("kind"))
        // 64-bit integers are encoded as decimal strings.
        assertTrue(s0.get("startTimeUnixNano") is String)
        assertEquals("1760000000000000000", s0.getString("startTimeUnixNano"))
        assertEquals("1760000060000000000", s0.getString("endTimeUnixNano"))
        val attrs = s0.getJSONArray("attributes")
        assertEquals("active", attrs.getJSONObject(0).getJSONObject("value").getString("stringValue"))
        val intValue = attrs.getJSONObject(1).getJSONObject("value").get("intValue")
        assertTrue(intValue is String)
        assertEquals("3", intValue)
        assertEquals(false, attrs.getJSONObject(2).getJSONObject("value").getBoolean("boolValue"))
        assertEquals(0.5, attrs.getJSONObject(3).getJSONObject("value").getDouble("doubleValue"), 0.0)
        val link = s0.getJSONArray("links").getJSONObject(0)
        assertEquals("f".repeat(32), link.getString("traceId"))
        assertEquals("e".repeat(16), link.getString("spanId"))

        val s1 = spans.getJSONObject(1)
        assertEquals(spanId, s1.getString("parentSpanId"))
        assertEquals("Chrome \"beta\"\n", s1.getString("name"))
        assertFalse(s1.has("links"))
    }

    @Test
    fun logsShape() {
        val json = OtlpJsonEncoder.encodeLogs(
            resource,
            scope,
            listOf(
                LogData(10, 20, Severity.WARN, "Device unlocked", listOf(attr("event.name", "activity.device.screen")), traceId, spanId),
                LogData(30, 40, Severity.INFO, "started", listOf(attr("event.name", "agent.start"))),
            ),
        )
        val records = JSONObject(json).getJSONArray("resourceLogs").getJSONObject(0)
            .getJSONArray("scopeLogs").getJSONObject(0).getJSONArray("logRecords")
        val r0 = records.getJSONObject(0)
        assertEquals("10", r0.getString("timeUnixNano"))
        assertEquals("20", r0.getString("observedTimeUnixNano"))
        assertEquals(13, r0.getInt("severityNumber"))
        assertEquals("WARN", r0.getString("severityText"))
        assertEquals("Device unlocked", r0.getJSONObject("body").getString("stringValue"))
        assertEquals(traceId, r0.getString("traceId"))
        assertEquals(spanId, r0.getString("spanId"))
        assertEquals("event.name", r0.getJSONArray("attributes").getJSONObject(0).getString("key"))
        val r1 = records.getJSONObject(1)
        assertFalse(r1.has("traceId"))
        assertFalse(r1.has("spanId"))
    }

    @Test
    fun metricsShape() {
        val json = OtlpJsonEncoder.encodeMetrics(
            resource,
            scope,
            listOf(
                SumMetric(
                    "activity.app.time",
                    "Time",
                    "s",
                    integer = false,
                    points = listOf(SumPoint(listOf(attr("activity.app.id", "com.example")), 100, 200, 12.5)),
                ),
                SumMetric("activity.device.unlocks", "Unlocks", "", integer = true, points = listOf(SumPoint(emptyList(), 100, 200, 4.0))),
            ),
        )
        val metrics = JSONObject(json).getJSONArray("resourceMetrics").getJSONObject(0)
            .getJSONArray("scopeMetrics").getJSONObject(0).getJSONArray("metrics")
        val m0 = metrics.getJSONObject(0)
        assertEquals("activity.app.time", m0.getString("name"))
        assertEquals("s", m0.getString("unit"))
        val sum = m0.getJSONObject("sum")
        assertEquals(2, sum.getInt("aggregationTemporality"))
        assertEquals(true, sum.getBoolean("isMonotonic"))
        val p = sum.getJSONArray("dataPoints").getJSONObject(0)
        assertEquals("100", p.getString("startTimeUnixNano"))
        assertEquals("200", p.getString("timeUnixNano"))
        assertEquals(12.5, p.getDouble("asDouble"), 0.0)
        assertEquals("com.example", p.getJSONArray("attributes").getJSONObject(0).getJSONObject("value").getString("stringValue"))

        val p1 = metrics.getJSONObject(1).getJSONObject("sum").getJSONArray("dataPoints").getJSONObject(0)
        assertEquals("4", p1.getString("asInt"))
        assertFalse(p1.has("asDouble"))
    }

    @Test
    fun escapesControlCharacters() {
        val w = JsonWriter().beginObject().field("k", "a\u0001b\\c ").endObject()
        assertEquals("{\"k\":\"a\\u0001b\\\\c\\u2028\"}", w.toString())
        assertEquals("a\u0001b\\c ", JSONObject(w.toString()).getString("k"))
    }

    @Test
    fun gzipRoundTrip() {
        val text = "{\"a\":\"日本語\"}"
        assertEquals(text, Gzip.decompress(Gzip.compress(text)))
    }
}
