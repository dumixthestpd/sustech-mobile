package edu.sustech.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import edu.sustech.mobile.core.memory.MemoryFile;
import edu.sustech.mobile.core.memory.MemoryStore;
import edu.sustech.mobile.core.memory.ShareIntent;
import edu.sustech.mobile.ui.bb.MaterialReaderActivity;
import edu.sustech.mobile.ui.pms.UploadActivity;

/** Device-only, synthetic files. Never logs in, uploads, or submits coursework. */
public class ShareFlowCheck extends Instrumentation {
    private File output;
    private boolean dashboard;
    private boolean search;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        dashboard = arguments != null && "dashboard".equals(arguments.getString("suite"));
        search = arguments != null && "search".equals(arguments.getString("suite"));
        start();
    }

    @Override public void onStart() {
        if (dashboard) { new DashboardCheck(this).run(); return; }
        if (search) { new SearchCheck(this).run(); return; }
        Bundle result = new Bundle();
        try {
            output = getTargetContext().getExternalFilesDir("share-check");
            if (output == null || (!output.isDirectory() && !output.mkdirs())) throw new Exception("No report directory");
            byte[] pdf = pdf();
            MemoryFile file = MemoryStore.INSTANCE.put("device-check-pdf", "Course handout.pdf", "application/pdf", pdf);
            Activity reader = preview(file);
            awaitText("Course handout.pdf");
            SystemClock.sleep(1200);
            screenshot("01-pdf-preview.png");
            runOnMainSync(() -> reader.findViewById(R.id.bb_next).performClick());
            awaitText(getTargetContext().getString(R.string.bb_page_of, 2, 2));
            screenshot("01b-pdf-second-page.png");
            runOnMainSync(() -> reader.findViewById(R.id.bb_prev).performClick());
            awaitText(getTargetContext().getString(R.string.bb_page_of, 1, 2));
            try (InputStream stream = getTargetContext().getContentResolver().openInputStream(ShareIntent.INSTANCE.uriOf(file))) {
                if (stream == null || !Arrays.equals(pdf, stream.readAllBytes())) throw new Exception("Mobile provider bytes differ");
            }

            // Go through the actual chooser, then a different installed package.
            runOnMainSync(() -> reader.findViewById(R.id.bb_share).performClick());
            awaitText("ShareSink");
            screenshot("02-share-targets.png");
            clickText("ShareSink");
            awaitText("RESULT: SHARE OK");
            awaitText(sha256(pdf));
            screenshot("03-external-read-and-seek.png");

            Bundle stage = new Bundle();
            stage.putString("stream", "External recipient verified; checking BB to PMS next.\n");
            sendStatus(1, stage);
            Activity again = preview(file);
            runOnMainSync(() -> again.findViewById(R.id.bb_share).performClick());
            String target = getTargetContext().getString(R.string.upload_share_target);
            awaitText(target);
            clickText(target);
            awaitText("Course handout.pdf");
            awaitText(getTargetContext().getString(R.string.upload_submit));
            screenshot("04-bb-to-pms.png");

            ByteArrayOutputStream word = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(word)) {
                zip.putNextEntry(new ZipEntry("word/document.xml"));
                zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                    + "<w:body><w:p><w:r><w:t>Sample course handout</w:t></w:r></w:p>"
                    + "<w:p><w:r><w:t>Word preview verification.</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            preview(MemoryStore.INSTANCE.put("device-check-word", "Course notes.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", word.toByteArray()));
            awaitText("Sample course handout");
            screenshot("05-word-preview.png");

            Bitmap bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(0xffe1f4ed);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xff08664d);
            paint.setTextSize(30);
            canvas.drawText("Course diagram", 45, 100, paint);
            canvas.drawCircle(300, 250, 70, paint);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            bitmap.recycle();
            preview(MemoryStore.INSTANCE.put("device-check-image", "Course diagram.png", "image/png", png.toByteArray()));
            awaitText("Course diagram.png");
            SystemClock.sleep(800);
            screenshot("06-image-preview.png");

            // A real separate process supplies its URI; Mobile must not rely on
            // MemoryStore or its own provider for incoming files from other apps.
            ActivityMonitor uploadMonitor = addMonitor(UploadActivity.class.getName(), null, false);
            getTargetContext().startActivity(new Intent().setClassName("edu.sustech.probe", "edu.sustech.probe.ProbeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            awaitText("1. Share (ACTION_SEND)");
            clickText("1. Share (ACTION_SEND)");
            awaitText(target);
            clickText(target);
            Activity upload = waitForMonitorWithTimeout(uploadMonitor, 10000);
            if (upload == null) throw new Exception("Incoming share did not open the upload activity");
            awaitText("probe.pdf");
            screenshot("07-external-to-pms.png");
            runOnMainSync(() -> upload.startActivity(new Intent(upload, UploadActivity.class)
                .setAction(Intent.ACTION_SEND).setType("application/pdf").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            awaitText(getTargetContext().getString(R.string.upload_no_file));
            screenshot("08-invalid-share-clears-selection.png");
            removeMonitor(uploadMonitor);

            Intent clipOnly = ShareIntent.INSTANCE.send(getTargetContext(), file);
            clipOnly.removeExtra(Intent.EXTRA_STREAM);
            clipOnly.setClass(upload, UploadActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            runOnMainSync(() -> upload.startActivity(clipOnly));
            awaitText("Course handout.pdf");
            screenshot("09-clipdata-receipt.png");
            result.putString("stream", "PASS: two-page PDF navigation, DOCX and image previews; Mobile provider bytes; chooser; external read + seek + SHA256; BB and external-provider to PMS; missing-file selection reset; ClipData-only receipt. No upload. Screenshots: " + output);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            android.util.Log.e("ShareFlowCheck", "Device verification failed", error);
            result.putString("stream", "FAIL: " + error);
            try { screenshot("failure.png"); } catch (Exception ignored) { }
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Activity preview(MemoryFile file) {
        return startActivitySync(MaterialReaderActivity.Companion.intent(getTargetContext(), file)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    }

    private void awaitText(String text) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            AccessibilityNodeInfo found = find(getUiAutomation().getRootInActiveWindow(), text);
            if (found != null) return;
            SystemClock.sleep(250);
        }
        throw new Exception("Missing screen text: " + text);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (node.getText() != null && node.getText().toString().contains(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private void clickText(String text) throws Exception {
        AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), text);
        while (node != null && !node.isClickable()) node = node.getParent();
        if (node == null || !node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new Exception("Cannot choose " + text);
        SystemClock.sleep(750);
    }

    private void screenshot(String name) throws Exception {
        SystemClock.sleep(700);
        Bitmap image = getUiAutomation().takeScreenshot();
        if (image == null) throw new Exception("Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(output, name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }

    private byte[] pdf() throws Exception {
        PdfDocument document = new PdfDocument();
        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(24);
        page.getCanvas().drawText("Sample course handout", 50, 100, paint);
        paint.setTextSize(14);
        page.getCanvas().drawText("Preview and sharing verification. No personal data.", 50, 145, paint);
        document.finishPage(page);
        PdfDocument.Page second = document.startPage(new PdfDocument.PageInfo.Builder(595, 842, 2).create());
        second.getCanvas().drawText("Second page of the course handout", 50, 100, paint);
        document.finishPage(second);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        document.writeTo(bytes);
        document.close();
        return bytes.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) value.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return value.toString();
    }
}
