package hu.orszembejelento.service.reports

import hu.orszembejelento.service.reports.domain.availableWorkflowActions
import hu.orszembejelento.service.reports.domain.canModerationDelete
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** brief §20-21/§30/§33/§38 - the exact action-visibility matrix by (status, role, ownership). */
class WorkflowActionAvailabilityTest {

    @Test
    fun `ARCHIVED is terminal - no actions for any role`() {
        listOf("SERVICE_USER", "MODERATOR", "SUPER_ADMIN").forEach { role ->
            val availability = availableWorkflowActions("ARCHIVED", role, isOwnAssignment = false)
            assertEquals(role, hu.orszembejelento.service.reports.domain.WorkflowActionAvailability(), availability)
        }
    }

    @Test
    fun `NEW - SERVICE_USER may claim, never close`() {
        val availability = availableWorkflowActions("NEW", "SERVICE_USER", isOwnAssignment = false)
        assertTrue(availability.claim)
        assertFalse(availability.close)
        assertFalse(availability.returnToNew)
        assertFalse(availability.reassign)
    }

    @Test
    fun `NEW - MODERATOR and SUPER_ADMIN may close directly, never claim`() {
        listOf("MODERATOR", "SUPER_ADMIN").forEach { role ->
            val availability = availableWorkflowActions("NEW", role, isOwnAssignment = false)
            assertFalse(role, availability.claim)
            assertTrue(role, availability.close)
            assertFalse(role, availability.reassign)
        }
    }

    @Test
    fun `IN_PROGRESS - the owning SERVICE_USER sees return and close, never reassign`() {
        val availability = availableWorkflowActions("IN_PROGRESS", "SERVICE_USER", isOwnAssignment = true)
        assertTrue(availability.returnToNew)
        assertTrue(availability.close)
        assertFalse(availability.reassign)
        assertFalse(availability.claim)
    }

    @Test
    fun `IN_PROGRESS - a SERVICE_USER who is not the current assignee sees no actions`() {
        val availability = availableWorkflowActions("IN_PROGRESS", "SERVICE_USER", isOwnAssignment = false)
        assertEquals(hu.orszembejelento.service.reports.domain.WorkflowActionAvailability(), availability)
    }

    @Test
    fun `IN_PROGRESS - MODERATOR and SUPER_ADMIN see reassign, return and close`() {
        listOf("MODERATOR", "SUPER_ADMIN").forEach { role ->
            val availability = availableWorkflowActions("IN_PROGRESS", role, isOwnAssignment = false)
            assertTrue(role, availability.reassign)
            assertTrue(role, availability.returnToNew)
            assertTrue(role, availability.close)
            assertFalse(role, availability.claim)
        }
    }

    @Test
    fun `moderation delete is offered only to MODERATOR and SUPER_ADMIN, never SERVICE_USER`() {
        assertFalse(canModerationDelete("SERVICE_USER"))
        assertTrue(canModerationDelete("MODERATOR"))
        assertTrue(canModerationDelete("SUPER_ADMIN"))
    }

    @Test
    fun `an unrecognized role never gets moderation delete`() {
        assertFalse(canModerationDelete("SOMETHING_NEW"))
    }
}
