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
import edu.sustech.mobile.bb.BbDoc
import edu.sustech.mobile.bb.BbFiles
import edu.sustech.mobile.core.memory.MemoryFd
import edu.sustech.mobile.core.memory.MemoryFile
import edu.sustech.mobile.core.memory.MemoryStore
import edu.sustech.mobile.core.memory.ShareIntent
import edu.sustech.mobile.core.runIo

/**
 * Reads a file that exists only in this process's memory.
 *
 * Three modes over the same bytes: PDF pages through [PdfRenderer] on a
 * callback-backed, seekable descriptor ([MemoryFd]); images as a bitmap; Word
 * documents and plain text as extracted text ([BbDoc]). The descriptor is the
 * same one the share flow hands to other apps, so the app's view and the other
 * app's view cannot disagree about the file. Nothing is written to storage.
 */
class MaterialReaderActivity : AppCompatActivity() {

    private var file: MemoryFile? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var pageIndex = 0

    private val renderLock = Any()

    private lateinit var image: ImageView
    private lateinit var text: TextView
    private lateinit var counter: TextView
    private lateinit var note: TextView
    private lateinit var prev: Button
    private lateinit var next: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_material_reader)

        val token = Uri.parse(intent.getStringExtra(EXTRA_URI).orEmpty()).pathSegments.firstOrNull()
        file = token?.let { MemoryStore.byToken(it) }

        image = findViewById(R.id.bb_page_image)
        text = findViewById(R.id.bb_page_text)
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

        val f = file
        if (f == null) {
            fail(getString(R.string.bb_gone))
            return
        }
        // Share stays usable even when reading fails: an app that can open this
        // type may still be the better place for it.
        when {
            f.mime == "application/pdf" || BbFiles.sniff(f.bytes) == "application/pdf" -> openPdf(f)
            f.mime.startsWith("image/") || BbFiles.sniff(f.bytes).startsWith("image/") -> openImage(f)
            BbDoc.isText(f.mime) || f.name.endsWith(".docx", ignoreCase = true) -> openText(f)
            else -> fail(getString(R.string.bb_preview_unsupported))
        }
    }

    private fun openPdf(f: MemoryFile) = runIo(
        block = {
            synchronized(renderLock) {
                val fd = MemoryFd.open(this@MaterialReaderActivity, f.bytes)
                val r = PdfRenderer(fd)
                closeQuietly()
                pfd = fd
                renderer = r
                pageCount = r.pageCount
                pageIndex = 0
                renderPage(r, 0)
            }
        },
        onOk = { bitmap -> showPageBitmap(bitmap) },
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
                showPageBitmap(bitmap)
            }
        },
        onErr = { error -> fail(getString(R.string.bb_pdf_failed, error.message.orEmpty())) },
    )

    private fun openText(f: MemoryFile) = runIo(
        block = {
            val raw = if (f.name.endsWith(".docx", ignoreCase = true) || f.mime == BbDoc.DOCX) {
                BbDoc.docxText(f.bytes)
            } else {
                String(f.bytes, Charsets.UTF_8)
            }
            raw?.trim()
        },
        onOk = { body ->
            if (body.isNullOrEmpty()) {
                fail(getString(R.string.bb_doc_empty))
            } else {
                image.visibility = View.GONE
                text.text = body
                text.visibility = View.VISIBLE
                counter.visibility = View.GONE
                note.text = getString(R.string.bb_doc_text_only)
                note.visibility = View.VISIBLE
                prev.visibility = View.GONE
                next.visibility = View.GONE
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
            onOk = { bitmap -> showPageBitmap(bitmap) },
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

    private fun showPageBitmap(bitmap: Bitmap) {
        text.visibility = View.GONE
        image.visibility = View.VISIBLE
        image.setImageBitmap(bitmap)
        counter.text = getString(R.string.bb_page_of, pageIndex + 1, pageCount)
        counter.visibility = View.VISIBLE
        note.visibility = View.GONE
        prev.visibility = View.VISIBLE
        next.visibility = View.VISIBLE
        prev.isEnabled = pageIndex > 0
        next.isEnabled = pageIndex < pageCount - 1
    }

    private fun fail(message: String) {
        note.text = message
        note.visibility = View.VISIBLE
        counter.visibility = View.GONE
        prev.visibility = View.GONE
        next.visibility = View.GONE
    }

    private fun share() {
        val f = file ?: return
        startActivity(
            ShareIntent.chooser(
                this,
                ShareIntent.send(this, f),
                getString(R.string.bb_share_with),
            ),
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
            Intent(context, MaterialReaderActivity::class.java)
                .putExtra(EXTRA_URI, ShareIntent.uriOf(file).toString())
    }
}
