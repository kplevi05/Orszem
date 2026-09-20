package hu.orszembejelento.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import hu.orszembejelento.app.ui.newreport.SuccessContent
import hu.orszembejelento.app.ui.newreport.SuccessInfo
import org.junit.Rule
import org.junit.Test

/** The success screen repeats the KSH-derived settlement name, so it carries the CC BY 4.0 attribution too. */
class SuccessContentAttributionComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun the_success_screen_shows_the_settlement_and_the_KSH_attribution() {
        compose.setContent {
            SuccessContent(
                info = SuccessInfo("R-1", "Fenyegetés", null, "Példafalva", "RECEIVED"),
                onNewReport = {},
                onViewHistory = {},
                onHome = {},
            )
        }
        compose.onNodeWithText("Település: Példafalva").assertIsDisplayed()
        compose.onNodeWithText("Településadatok forrása: KSH (CC BY 4.0)").assertIsDisplayed()
    }
}
