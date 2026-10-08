package net.ymotongpoo.activitylog.otlp

/**
 * Minimal streaming JSON writer. It only tracks whether a separator is needed, so
 * callers are responsible for producing a well-formed structure.
 */
class JsonWriter {
    private val sb = StringBuilder()
    private val first = ArrayDeque<Boolean>()
    private var afterName = false

    fun beginObject(): JsonWriter = open('{')
    fun endObject(): JsonWriter = close('}')
    fun beginArray(): JsonWriter = open('[')
    fun endArray(): JsonWriter = close(']')

    fun name(name: String): JsonWriter {
        separator()
        quote(name)
        sb.append(':')
        afterName = true
        return this
    }

    fun value(value: String): JsonWriter {
        separator()
        quote(value)
        return this
    }

    fun value(value: Boolean): JsonWriter {
        separator()
        sb.append(if (value) "true" else "false")
        return this
    }

    fun value(value: Long): JsonWriter {
        separator()
        sb.append(value)
        return this
    }

    fun value(value: Double): JsonWriter {
        separator()
        // JSON has no NaN/Infinity; OTLP JSON accepts them as strings.
        when {
            value.isNaN() -> quote("NaN")
            value == Double.POSITIVE_INFINITY -> quote("Infinity")
            value == Double.NEGATIVE_INFINITY -> quote("-Infinity")
            else -> sb.append(value.toString())
        }
        return this
    }

    fun field(name: String, value: String): JsonWriter = name(name).value(value)
    fun field(name: String, value: Long): JsonWriter = name(name).value(value)
    fun field(name: String, value: Boolean): JsonWriter = name(name).value(value)
    fun field(name: String, value: Double): JsonWriter = name(name).value(value)

    override fun toString(): String = sb.toString()

    private fun open(c: Char): JsonWriter {
        separator()
        sb.append(c)
        first.addLast(true)
        return this
    }

    private fun close(c: Char): JsonWriter {
        first.removeLast()
        sb.append(c)
        return this
    }

    private fun separator() {
        if (afterName) {
            afterName = false
            return
        }
        if (first.isEmpty()) return
        if (first.last()) {
            first[first.size - 1] = false
        } else {
            sb.append(',')
        }
    }

    private fun quote(s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ' || ch == ' ' || ch == ' ') {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }
}
