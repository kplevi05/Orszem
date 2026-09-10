package hu.orszembejelento.service.common

import hu.orszembejelento.service.R
import hu.orszembejelento.service.common.ui.roleLabelRes
import hu.orszembejelento.service.common.ui.userStatusLabelRes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The single presentation mapping for backend role / user-status enums. The raw values must
 * never reach the UI; an unknown value still maps to a safe, non-leaking label.
 */
class RoleStatusLabelsTest {

    @Test
    fun `every role enum maps to its own Hungarian label`() {
        assertEquals(R.string.role_service_user, roleLabelRes("SERVICE_USER"))
        assertEquals(R.string.role_moderator, roleLabelRes("MODERATOR"))
        assertEquals(R.string.role_super_admin, roleLabelRes("SUPER_ADMIN"))
    }

    @Test
    fun `every user-status enum maps to its own Hungarian label`() {
        assertEquals(R.string.user_status_active, userStatusLabelRes("ACTIVE"))
        assertEquals(R.string.user_status_deactivated, userStatusLabelRes("DEACTIVATED"))
    }

    @Test
    fun `an unknown value never falls through to a raw string`() {
        // A newer backend enum must still render a real localized label, never "GHOST_ROLE".
        assertEquals(R.string.role_service_user, roleLabelRes("GHOST_ROLE"))
        assertEquals(R.string.user_status_active, userStatusLabelRes("SUSPENDED"))
    }
}
