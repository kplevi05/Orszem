package hu.orszembejelento.service.common

import hu.orszembejelento.service.common.ui.userIdentityLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class UserIdentityLabelTest {
    @Test
    fun `nickname is appended directly after the canonical service id`() {
        assertEquals("SZ-408468(Levente)", userIdentityLabel("SZ-408468", "Levente"))
    }

    @Test
    fun `missing or blank nickname leaves the service id unchanged`() {
        assertEquals("SZ-408468", userIdentityLabel("SZ-408468", null))
        assertEquals("SZ-408468", userIdentityLabel("SZ-408468", "   "))
    }
}
