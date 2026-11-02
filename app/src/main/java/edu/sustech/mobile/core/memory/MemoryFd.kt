package edu.sustech.mobile.core.memory

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager

/**
 * A read-only **seekable** file descriptor over bytes that live in this process
 * only — no file, no cache copy, nothing a file manager can see.
 *
 * The provider framework turns `lseek` + `read` into [ProxyFileDescriptorCallback.onRead],
 * which is what lets `PdfRenderer` and a receiving app behave as if this were a
 * real file. Requires API 26 (`minSdk` is 26).
 */
object MemoryFd {

    fun open(
        context: Context,
        bytes: ByteArray,
        onRelease: () -> Unit = {},
    ): ParcelFileDescriptor {
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = bytes.size.toLong()

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (offset < 0 || offset >= bytes.size) return 0
                val from = offset.toInt()
                val n = minOf(size, bytes.size - from)
                System.arraycopy(bytes, from, data, 0, n)
                return n
            }

            override fun onRelease() = onRelease()
        }
        return context.getSystemService(StorageManager::class.java)
            .openProxyFileDescriptor(
                ParcelFileDescriptor.parseMode("r"),
                callback,
                Handler(Looper.getMainLooper()),
            )
    }
}
