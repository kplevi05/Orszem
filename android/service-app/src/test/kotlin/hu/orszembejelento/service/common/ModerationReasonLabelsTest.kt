package hu.orszembejelento.service.common

import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.MODERATION_REASONS
import hu.orszembejelento.service.common.ui.moderationReasonLabelRes
import hu.orszembejelento.service.common.ui.restoreDialogTextRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Phase 9 brief §4-5/§39/§48-49 - the frozen reason vocabulary and the restore-copy rule. */
class ModerationReasonLabelsTest {

    @Test
    fun `every one of the six frozen reasons maps to its own Hungarian label`() {
        val expected = mapOf(
            "SPAM" to R.string.moderation_reason_spam,
            "TROLL_OR_FALSE_REPORT" to R.string.moderation_reason_troll_or_false_report,
            "DUPLICATE" to R.string.moderation_reason_duplicate,
            "INCORRECT" to R.string.moderation_reason_incorrect,
            "IRRELEVANT" to R.string.moderation_reason_irrelevant,
            "OTHER" to R.string.moderation_reason_other,
        )
        expected.forEach { (reason, res) -> assertEquals(reason, res, moderationReasonLabelRes(reason)) }
    }

    @Test
    fun `every mapped reason produces a distinct label - no silent collapsing`() {
        val labels = MODERATION_REASONS.map { moderationReasonLabelRes(it) }
        assertEquals("every reason must map to a distinct string resource", labels.size, labels.toSet().size)
    }

    @Test
    fun `an unrecognized reason still renders a safe label, never a raw code or a crash`() {
        assertEquals(R.string.moderation_reason_other, moderationReasonLabelRes("SOMETHING_NEW"))
    }

    @Test
    fun `MODERATION_REASONS is exactly the frozen six, in the dialog's fixed presentation order`() {
        assertEquals(
            listOf("SPAM", "TROLL_OR_FALSE_REPORT", "DUPLICATE", "INCORRECT", "IRRELEVANT", "OTHER"),
            MODERATION_REASONS,
        )
    }

    @Test
    fun `the restore dialog shows distinct copy for each status-before-delete case`() {
        assertEquals(R.string.restore_dialog_text_new, restoreDialogTextRes("NEW"))
        assertEquals(R.string.restore_dialog_text_in_progress, restoreDialogTextRes("IN_PROGRESS"))
        assertEquals(R.string.restore_dialog_text_archived, restoreDialogTextRes("ARCHIVED"))
        assertNotEquals(restoreDialogTextRes("NEW"), restoreDialogTextRes("IN_PROGRESS"))
        assertNotEquals(restoreDialogTextRes("NEW"), restoreDialogTextRes("ARCHIVED"))
        assertNotEquals(restoreDialogTextRes("IN_PROGRESS"), restoreDialogTextRes("ARCHIVED"))
    }

    @Test
    fun `an unrecognized status-before-delete falls back to the NEW copy, never a crash`() {
        assertEquals(R.string.restore_dialog_text_new, restoreDialogTextRes("SOMETHING_NEW"))
    }
}
