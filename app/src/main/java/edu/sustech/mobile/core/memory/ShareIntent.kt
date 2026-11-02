package edu.sustech.mobile.core.memory

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The two hand-offs the user asked for: **share to an app** (a chat assistant,
 * a chat app) and **open with another app** (a PDF reader).
 *
 * Both carry a `content://` URI from [MemoryDocumentProvider] plus a one-shot
 * read grant. ClipData is what actually delivers the grant to several targets;
 * EXTRA_STREAM alone is not enough on every recipient.
 */
object ShareIntent {

    const val AUTHORITY = "edu.sustech.mobile.memory"

    fun uriOf(memory: MemoryFile): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(AUTHORITY)
            .appendPath(memory.token)
            .appendPath(memory.name)
            .build()

    /** ACTION_SEND — what a chat assistant receives. */
    fun send(context: Context, memory: MemoryFile): Intent {
        val uri = uriOf(memory)
        return Intent(Intent.ACTION_SEND).apply {
            type = memory.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, memory.name)
            putExtra(Intent.EXTRA_SUBJECT, memory.name)
            clipData = ClipData.newUri(context.contentResolver, memory.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** ACTION_VIEW — "open in another app". */
    fun view(context: Context, memory: MemoryFile): Intent {
        val uri = uriOf(memory)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, memory.mime)
            clipData = ClipData.newUri(context.contentResolver, memory.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * The chooser both screens show.
     *
     * Targets are left exactly as the system ranks them. Filtering the Windows
     * bridges out of a _share_ was tried and reverted: on WSA they are the only
     * receivers for a .docx, so the chooser closed instantly and the tap looked
     * dead. Accidentally opening Office happened on paths that are now gone —
     * a file row opens the in-app reader, and there is no "open with" button.
     */
    fun chooser(context: Context, target: Intent, title: CharSequence): Intent =
        Intent.createChooser(target, title)
}
