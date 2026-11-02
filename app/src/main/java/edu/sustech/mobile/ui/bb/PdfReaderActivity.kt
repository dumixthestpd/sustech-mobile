package edu.sustech.mobile.ui.bb

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import edu.sustech.mobile.R
import edu.sustech.mobile.bb.BbFiles
import edu.sustech.mobile.core.memory.MemoryFd
import edu.sustech.mobile.core.memory.MemoryFile
import edu.sustech.mobile.core.memory.MemoryStore
import edu.sustech.mobile.core.memory.ShareIntent
import edu.sustech.mobile.core.runIo

/**
 * Reads a file that exists only in this process's memory.
 *
 * The renderer consumes the same callback-backed, seekable descriptor the share
 * flow hands to other apps ([MemoryFd]), so preview and share cannot disagree
 * about the bytes. Nothing is written to storage on either path.
 */
class PdfReaderActivity : AppCompatActivity() {

    private var file: MemoryFile? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var pageIndex = 0

    private val renderLock = Any()

    private lateinit var image: ImageView
    private lateinit var counter: TextView
    private lateinit var note: TextView
    private lateinit var prev: Button
    private lateinit var next: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_reader)

        val token = Uri.parse(intent.getStringExtra(EXTRA_URI).orEmpty()).pathSegments.firstOrNull()
        file = token?.let { MemoryStore.byToken(it) }

        image = findViewById(R.id.bb_page_image)
        counter = findViewById(R.id.bb_page_of)
        note = findViewById(R.id.bb_pdf_note)
        prev = findViewById(R.id.bb_prev)
        next = findViewById(R.id.bb_next)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = file?.name ?: getString(R.string.bb_preview)
        toolbar.setNavigationOnClickListener { finish() }

        prev.setOnClickListener { showPage(pageIndex - 1) }
        next.setOnClickListener { showPage(pageIndex + 1) }
        findViewById<View>(R.id.bb_share).setOnClickListener { share() }
        findViewById<View>(R.id.bb_open_with).setOnClickListener { openWith() }

        val f = file
        if (f == null) {
            fail(getString(R.string.bb_gone))
            return
        }
        // Share stays usable even when preview fails: an app that can read this
        // type may still be the better place to open it.
        when {
            f.mime == "application/pdf" || BbFiles.sniff(f.bytes) == "application/pdf" -> openPdf(f)
            f.mime.startsWith("image/") || BbFiles.sniff(f.bytes).startsWith("image/") -> openImage(f)
            else -> fail(getString(R.string.bb_preview_unsupported))
        }
    }

    private fun openPdf(f: MemoryFile) = runIo(
        block = {
            synchronized(renderLock) {
                val fd = MemoryFd.open(this@PdfReaderActivity, f.bytes)
                val r = PdfRenderer(fd)
                closeQuietly()
                pfd = fd
                renderer = r
                pageCount = r.pageCount
                pageIndex = 0
                renderPage(r, 0)
            }
        },
        onOk = { bitmap -> show(bitmap) },
        onErr = { error ->
            fail(getString(R.string.bb_pdf_failed, error.message ?: error::class.java.simpleName))
        },
    )

    private fun openImage(f: MemoryFile) = runIo(
        block = { BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size) },
        onOk = { bitmap ->
            if (bitmap == null) {
                fail(getString(R.string.bb_pdf_failed, "undecodable image"))
            } else {
                pageCount = 1
                pageIndex = 0
                show(bitmap)
            }
        },
        onErr = { error -> fail(getString(R.string.bb_pdf_failed, error.message.orEmpty())) },
    )

    private fun showPage(index: Int) {
        if (index < 0 || index >= pageCount) return
        val r = renderer ?: return
        pageIndex = index
        runIo(
            block = { synchronized(renderLock) { renderPage(r, index) } },
            onOk = { bitmap -> show(bitmap) },
            onErr = { error -> fail(getString(R.string.bb_pdf_failed, error.message.orEmpty())) },
        )
    }

    private fun renderPage(r: PdfRenderer, index: Int): Bitmap {
        r.openPage(index).use { page ->
            val width = (resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: 1080)
                .coerceAtLeast(320)
            val height = (page.height.toFloat() * width / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    private fun show(bitmap: Bitmap) {
        image.setImageBitmap(bitmap)
        counter.text = getString(R.string.bb_page_of, pageIndex + 1, pageCount)
        counter.visibility = View.VISIBLE
        note.visibility = View.GONE
        prev.isEnabled = pageIndex > 0
        next.isEnabled = pageIndex < pageCount - 1
    }

    private fun fail(message: String) {
        note.text = message
        note.visibility = View.VISIBLE
        counter.visibility = View.GONE
        prev.isEnabled = false
        next.isEnabled = false
    }

    private fun share() {
        val f = file ?: return
        startActivity(
            Intent.createChooser(
                ShareIntent.send(this, f),
                getString(R.string.bb_share_with),
            ),
        )
    }

    private fun openWith() {
        val f = file ?: return
        startActivity(
            Intent.createChooser(ShareIntent.view(this, f), getString(R.string.bb_open_with)),
        )
    }

    private fun closeQuietly() {
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
        renderer = null
        pfd = null
    }

    override fun onDestroy() {
        synchronized(renderLock) { closeQuietly() }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URI = "uri"

        fun intent(context: Context, file: MemoryFile): Intent =
            Intent(context, PdfReaderActivity::class.java)
                .putExtra(EXTRA_URI, ShareIntent.uriOf(file).toString())
    }
}
