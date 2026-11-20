package edu.sustech.mobile.pms

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** A private multipart-upload copy of a file received through a temporary URI grant. */
object IncomingPrintFile {
    class TooLarge : IOException("Print file exceeds the size limit")

    fun copy(
        cacheDir: File,
        displayName: String?,
        maxBytes: Long = 200L * 1024 * 1024,
        openStream: () -> InputStream?,
    ): File {
        val base = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        val name = base.takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "upload.bin"
        // Separate directories preserve the original name without overwriting
        // another selected file or an upload that is already in progress.
        val directory = Files.createTempDirectory(cacheDir.toPath(), "pms-share-").toFile()
        val target = File(directory, name)
        try {
            val input = openStream() ?: throw IOException("No readable file stream")
            input.use {
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = it.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maxBytes) throw TooLarge()
                        output.write(buffer, 0, read)
                    }
                }
            }
            return target
        } catch (error: Exception) {
            discard(target)
            throw error
        }
    }

    fun discard(file: File) {
        file.delete()
        file.parentFile?.delete()
    }
}
