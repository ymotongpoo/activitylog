package net.ymotongpoo.activitylog.storage

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Persist directory entries as well as file contents before acknowledging a write. */
object DurableFiles {
    fun syncDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}
