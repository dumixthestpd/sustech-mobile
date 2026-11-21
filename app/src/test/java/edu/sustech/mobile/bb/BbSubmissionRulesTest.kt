package edu.sustech.mobile.bb

import org.junit.Assert.*
import org.junit.Test

class BbSubmissionRulesTest {
    @Test fun `drafts stay pending but one completed or awaiting attempt removes an assignment`() {
        assertEquals(SubmissionState.UNSUBMITTED, BbSubmissionRules.state(emptyList()))
        assertEquals(SubmissionState.DRAFT, BbSubmissionRules.state(listOf("InProgress")))
        assertEquals(SubmissionState.SUBMITTED, BbSubmissionRules.state(listOf("NeedsGrading", "InProgress")))
        assertEquals(SubmissionState.SUBMITTED, BbSubmissionRules.state(listOf("Completed", "NeedsGradingAgain")))
    }
    @Test fun `missing ownership or unexpected statuses cannot become unsubmitted`() {
        assertEquals(SubmissionState.UNKNOWN, BbSubmissionRules.state(emptyList(), false))
        assertEquals(SubmissionState.UNKNOWN, BbSubmissionRules.state(listOf("new-server-status")))
    }
    @Test fun `pending grade remains pending even when a provisional score exists`() {
        assertTrue(BbAttempt("1", "NeedsGrading", "0", "", "").pendingGrade)
        assertFalse(BbAttempt("1", "Completed", "0", "", "").pendingGrade)
    }
}
