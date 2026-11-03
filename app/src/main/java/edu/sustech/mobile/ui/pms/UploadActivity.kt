package edu.sustech.mobile.ui.pms

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.pms.ColorMode
import edu.sustech.mobile.pms.Duplex
import edu.sustech.mobile.pms.Paper
import edu.sustech.mobile.pms.UploadOptions
import java.io.File

/**
 * 云打印 upload: pick a file, choose the five print options, send it to the
 * queue. Uploading never charges money — the charge happens at pickup.
 */
class UploadActivity : AppCompatActivity() {

    private var picked: File? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyToCache(uri)
    }

    private val colors = listOf(ColorMode.BW, ColorMode.COLOR)
    private val papers = listOf(Paper.UNSPECIFIED, Paper.A4, Paper.A3)
    private val duplexes = listOf(Duplex.SINGLE, Duplex.SHORT_EDGE, Duplex.LONG_EDGE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(applicationContext)
        setContentView(R.layout.activity_upload)

        val colorSpinner = findViewById<Spinner>(R.id.spinner_color)
        colorSpinner.adapter = spinnerAdapter(listOf(getString(R.string.upload_color_bw), getString(R.string.upload_color_color)))

        findViewById<Spinner>(R.id.spinner_paper).adapter = spinnerAdapter(
            listOf(getString(R.string.upload_paper_any), "A4", "A3"),
        )

        findViewById<Spinner>(R.id.spinner_duplex).adapter = spinnerAdapter(
            listOf(
                getString(R.string.upload_duplex_single),
                getString(R.string.upload_duplex_short),
                getString(R.string.upload_duplex_long),
            ),
        )

        findViewById<MaterialButton>(R.id.btn_pick).setOnClickListener {
            picker.launch(arrayOf("application/pdf", "image/*", "application/msword", "text/plain", "*/*"))
        }

        findViewById<MaterialButton>(R.id.btn_upload).setOnClickListener { submit() }

        handleIncomingShare(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun spinnerAdapter(labels: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    /** SAF hands back a content:// URI — the multipart body needs a real file. */
    private fun copyToCache(uri: Uri) {
        try {
            val name = displayName(uri) ?: "upload.bin"
            val target = File(cacheDir, name)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "no stream" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            picked = target
            findViewById<TextView>(R.id.file_name).text = name
        } catch (e: Exception) {
            Toast.makeText(this, R.string.pick_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        } ?: return
        copyToCache(uri)
    }

    private fun submit() {
        val file = picked
        if (file == null) {
            Toast.makeText(this, R.string.upload_no_file, Toast.LENGTH_SHORT).show()
            return
        }
        val options = UploadOptions(
            color = colors.getOrElse(findViewById<Spinner>(R.id.spinner_color).selectedItemPosition) { ColorMode.BW },
            paper = papers.getOrElse(findViewById<Spinner>(R.id.spinner_paper).selectedItemPosition) { Paper.UNSPECIFIED },
            duplex = duplexes.getOrElse(findViewById<Spinner>(R.id.spinner_duplex).selectedItemPosition) { Duplex.SINGLE },
            pageFrom = findViewById<EditText>(R.id.input_page_from).text.toString().trim().toIntOrNull() ?: 0,
            pageTo = findViewById<EditText>(R.id.input_page_to).text.toString().trim().toIntOrNull() ?: 0,
            copies = findViewById<EditText>(R.id.input_copies).text.toString().trim().toIntOrNull() ?: 1,
        ).normalised()

        val progress = findViewById<ProgressBar>(R.id.upload_progress)
        val button = findViewById<MaterialButton>(R.id.btn_upload)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        button.isEnabled = false
        button.setText(R.string.upload_running)

        runIo(
            block = {
                App.api.upload(file, options) { sent, total ->
                    val percent = if (total > 0) ((sent * 100) / total).toInt() else 0
                    runOnUiThread { progress.progress = percent }
                }
            },
            onOk = {
                progress.visibility = View.GONE
                button.isEnabled = true
                button.setText(R.string.upload_submit)
                Toast.makeText(this, getString(R.string.upload_ok, file.name), Toast.LENGTH_LONG).show()
                finish()
            },
            onErr = { error ->
                progress.visibility = View.GONE
                button.isEnabled = true
                button.setText(R.string.upload_submit)
                Toast.makeText(this, getString(R.string.upload_failed, error.friendly(this)), Toast.LENGTH_LONG).show()
            },
        )
    }
}
