package hu.orszembejelento.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner-approved wording for the two production strings added in the V2.0.1 patch. Pinned
 * exactly, so a later edit cannot quietly change either of them.
 */
@RunWith(AndroidJUnit4::class)
class ApprovedCopyInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_rate_limit_message_is_the_approved_text() {
        assertEquals(
            "Túl sok bejelentés érkezett rövid időn belül. Kérjük, várjon egy kicsit, majd próbálja újra.",
            context.getString(R.string.error_rate_limited),
        )
    }

    @Test
    fun the_ksh_attribution_is_the_approved_text() {
        assertEquals("Településadatok forrása: KSH (CC BY 4.0)", context.getString(R.string.settlement_data_source))
    }
}
