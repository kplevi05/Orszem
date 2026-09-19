package hu.orszembejelento.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The owner-approved KSH attribution (CC BY 4.0) shown beside the settlement data the Service
 * app presents. Pinned exactly so a later edit cannot quietly change it.
 */
@RunWith(AndroidJUnit4::class)
class ApprovedCopyInstrumentedTest {

    @Test
    fun the_ksh_attribution_is_the_approved_text() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Településadatok forrása: KSH (CC BY 4.0)", context.getString(R.string.settlement_data_source))
    }
}
