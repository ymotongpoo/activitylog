package net.ymotongpoo.activitylog.work

import android.util.AtomicFile
import net.ymotongpoo.activitylog.collect.PersistedState
import net.ymotongpoo.activitylog.collect.StateCodec
import java.io.File
import java.io.FileNotFoundException

/** Stores [PersistedState] atomically so a crash never leaves a half-written file. */
class StateStore(file: File) {
    private val atomic = AtomicFile(file)

    /** Returns null when there is no state yet; throws when it is corrupt. */
    fun load(): PersistedState? {
        val bytes = try {
            atomic.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        return StateCodec.decode(bytes.toString(Charsets.UTF_8))
    }

    fun save(state: PersistedState) {
        val out = atomic.startWrite()
        try {
            out.write(StateCodec.encode(state).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }
}
