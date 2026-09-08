package hu.orszembejelento.app.report

import hu.orszembejelento.app.report.domain.ReportAccessCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAccessCredentialTest {

    private val pattern = Regex("^pr_[A-Za-z0-9_-]{43}$")

    @Test
    fun `a generated credential matches the exact backend shape`() {
        val credential = ReportAccessCredential.generate()
        assertTrue(pattern.matches(credential.value))
    }

    @Test
    fun `two generated credentials are never equal - real SecureRandom, not a deterministic generator`() {
        val a = ReportAccessCredential.generate()
        val b = ReportAccessCredential.generate()
        assertNotEquals(a.value, b.value)
    }

    @Test
    fun `generating many credentials never collides and never produces a malformed value`() {
        val seen = HashSet<String>()
        repeat(500) {
            val credential = ReportAccessCredential.generate()
            assertTrue(pattern.matches(credential.value))
            assertTrue("collision or duplicate at iteration $it", seen.add(credential.value))
        }
    }

    @Test
    fun `toString never leaks the credential value`() {
        val credential = ReportAccessCredential.generate()
        assertTrue(credential.toString().contains("redacted"))
        assertTrue(!credential.toString().contains(credential.value))
    }

    @Test
    fun `ofStored accepts a well-formed value and round-trips it exactly`() {
        val generated = ReportAccessCredential.generate()
        val reconstructed = ReportAccessCredential.ofStored(generated.value)
        assertNotNull(reconstructed)
        assertEquals(generated.value, reconstructed!!.value)
    }

    @Test
    fun `ofStored rejects anything not matching the exact shape`() {
        listOf(
            "not-even-close",
            "pr_tooshort",
            "wrongprefix_" + "x".repeat(43),
            "pr_" + "x".repeat(42),
            "pr_" + "x".repeat(44),
            "pr_" + "!".repeat(43),
            "",
        ).forEach { malformed ->
            assertNull("expected '$malformed' to be rejected", ReportAccessCredential.ofStored(malformed))
        }
    }
}
