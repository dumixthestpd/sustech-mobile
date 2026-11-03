package edu.sustech.mobile.bb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The submission form is the one place where a wrong field means a file that
 * silently never reaches the course, so the parsing rules are pinned here.
 */
class BbSubmitTest {

    private val html = """
        <form id="uploadAssignmentFormId" action="/webapps/assignment/uploadAssignment?action=submit">
          <input type="hidden" name="blackboard.platform.security.NonceUtil.nonce" value="abc123">
          <input type="hidden" name="attempt_id" value="">
          <input type="hidden" name="course_id" value="_8613_1">
          <input type="checkbox" name="send_email" value="true">
          <input type="checkbox" name="keep_copy" value="true" checked>
          <input type="file" id="newFile_LocalFile0" name="newFile_LocalFile0">
          <input type="submit" name="dispatch" value="save">
          <textarea name="student_comments">draft</textarea>
        </form>
    """.trimIndent()

    @Test
    fun `hidden fields survive`() {
        val form = BbSubmit.parse(html)
        assertEquals("abc123", form.fields["blackboard.platform.security.NonceUtil.nonce"])
        assertEquals("_8613_1", form.fields["course_id"])
        assertTrue(form.fields.containsKey("attempt_id"))
    }

    @Test
    fun `buttons and the file input are not form data`() {
        val fields = BbSubmit.parse(html).fields
        assertFalse(fields.containsKey("dispatch"))
        assertFalse(fields.containsKey("newFile_LocalFile0"))
    }

    @Test
    fun `only a checked box is submitted`() {
        val fields = BbSubmit.parse(html).fields
        assertFalse(fields.containsKey("send_email"))
        assertEquals("true", fields["keep_copy"])
    }

    @Test
    fun `editor fields exist even when empty`() {
        val fields = BbSubmit.parse("<textarea name='student_comments'></textarea>").fields
        assertEquals("", fields["student_comments"])
    }

    @Test
    fun `staging adds exactly what BB's picker adds`() {
        val staged = BbSubmit.stage(BbSubmit.parse(html), "report.pdf")
        assertEquals("L", staged.fields["newFile_attachmentType"])
        assertEquals("new", staged.fields["newFile_fileId"])
        assertEquals("undefined", staged.fields["newFile_artifactFileId"])
        assertEquals("undefined", staged.fields["newFile_artifactType"])
        assertEquals("undefined", staged.fields["newFile_artifactTypeResourceKey"])
        assertEquals("report.pdf", staged.fields["newFile_linkTitle"])
        assertEquals("submit", staged.fields["dispatch"])
        assertEquals(BbSubmit.FILE_PART, staged.filePart)
    }

    @Test
    fun `a form without the nonce is refused`() {
        assertFalse(BbSubmit.isUsable(BbSubmit.parse("<form></form>")))
        assertTrue(BbSubmit.isUsable(BbSubmit.parse(html)))
    }
}
