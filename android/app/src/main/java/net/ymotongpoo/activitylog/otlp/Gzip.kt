package net.ymotongpoo.activitylog.otlp

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object Gzip {
    fun compress(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    fun decompress(data: ByteArray): String =
        GZIPInputStream(data.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
}
