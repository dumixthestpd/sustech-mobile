package edu.sustech.mobile.bb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BbDocTest {

    private fun docx(bodyXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(bodyXml.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/styles.xml"))
            zip.write("<w:styles/>".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `paragraphs become lines and formatting is dropped`() {
        val xml = "<w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Principles</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>Do this</w:t></w:r><w:r><w:tab/><w:t>in class</w:t></w:r></w:p></w:body>"
        assertEquals("Principles\nDo this\tin class", BbDoc.textOfXml(xml))
    }

    @Test
    fun `entities are decoded`() {
        val xml = "<w:p><w:t>A &amp; B &lt;x&gt; &#65;</w:t></w:p>"
        assertEquals("A & B <x> A", BbDoc.textOfXml(xml))
    }

    @Test
    fun `a docx is read through its zip entry`() {
        val text = BbDoc.docxText(docx("<w:p><w:t>Experiment X</w:t></w:p>"))
        assertEquals("Experiment X", text)
    }

    @Test
    fun `a zip without a document body is not text`() {
        assertNull(BbDoc.docxText(byteArrayOf(1, 2, 3)))
        assertTrue(BbDoc.isText(BbDoc.DOCX))
        assertTrue(BbDoc.isText("text/plain"))
    }
}
