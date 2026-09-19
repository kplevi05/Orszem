package hu.orszembejelento.service.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hu.orszembejelento.service.common.ui.SettlementDataSourceNote
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The KSH attribution (CC BY 4.0) must show the approved wording AND open the address named in
 * the licence's own required attribution string when tapped. A recording [UriHandler] stands in
 * for the browser, so the test proves exactly what would be opened.
 */
class SettlementDataSourceNoteComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val approvedText = "Településadatok forrása: KSH (CC BY 4.0)"

    private class RecordingUriHandler : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) { opened += uri }
    }

    @Test
    fun it_shows_the_approved_wording() {
        compose.setContent { SettlementDataSourceNote(color = Color.DarkGray) }
        compose.onNodeWithText(approvedText).assertIsDisplayed()
    }

    @Test
    fun tapping_it_opens_exactly_the_documented_KSH_address_once() {
        val handler = RecordingUriHandler()
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides handler) { SettlementDataSourceNote(color = Color.DarkGray) }
        }

        compose.onNodeWithText(approvedText).performClick()

        assertEquals(listOf("https://www.ksh.hu"), handler.opened)
    }
}
