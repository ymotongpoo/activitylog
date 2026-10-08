package net.ymotongpoo.activitylog.collect

import java.security.SecureRandom
import java.util.Random

interface IdGenerator {
    /** 16 random bytes as 32 lowercase hex characters. */
    fun traceId(): String

    /** 8 random bytes as 16 lowercase hex characters. */
    fun spanId(): String
}

class RandomIdGenerator(private val random: Random = SecureRandom()) : IdGenerator {
    override fun traceId(): String = hex(16)
    override fun spanId(): String = hex(8)

    private fun hex(size: Int): String {
        val bytes = ByteArray(size)
        do {
            random.nextBytes(bytes)
        } while (bytes.all { it == 0.toByte() })
        val out = CharArray(size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
