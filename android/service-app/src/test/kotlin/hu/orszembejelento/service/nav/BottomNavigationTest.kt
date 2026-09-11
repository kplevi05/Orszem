package hu.orszembejelento.service.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** The frozen four-destination navigation matrix per role (brief §5). */
class BottomNavigationTest {

    @Test
    fun `SERVICE_USER gets Reports, Archive, Stats, Profile`() {
        assertEquals(listOf(Routes.REPORTS, Routes.ARCHIVE, Routes.STATS, Routes.PROFILE), bottomRoutesFor("SERVICE_USER"))
    }

    @Test
    fun `MODERATOR gets Reports, Archive, Stats, Moderation`() {
        assertEquals(listOf(Routes.REPORTS, Routes.ARCHIVE, Routes.STATS, Routes.MODERATION), bottomRoutesFor("MODERATOR"))
    }

    @Test
    fun `SUPER_ADMIN gets Reports, Archive, Stats, Admin`() {
        assertEquals(listOf(Routes.REPORTS, Routes.ARCHIVE, Routes.STATS, Routes.ADMIN), bottomRoutesFor("SUPER_ADMIN"))
    }

    @Test
    fun `every role gets exactly four destinations`() {
        listOf("SERVICE_USER", "MODERATOR", "SUPER_ADMIN").forEach { role ->
            assertEquals("role=$role", 4, bottomRoutesFor(role).size)
        }
    }

    @Test
    fun `an unrecognised role falls back to the Profile destination, never a privileged one`() {
        assertEquals(Routes.PROFILE, bottomRoutesFor("SOMETHING_UNEXPECTED").last())
    }
}
