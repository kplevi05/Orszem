package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import hu.orszembejelento.service.auth.ui.AccountScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NicknameComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun super_admin_profile_shows_the_formatted_identity_and_can_submit_a_nickname() {
        var submitted: String? = null
        compose.setContent {
            AccountScreen(
                serviceId = "SZ-408468",
                nickname = "Régi",
                role = "SUPER_ADMIN",
                busy = false,
                error = null,
                onChangePassword = { _, _, _ -> },
                onChangeNickname = { value, done -> submitted = value; done(true) },
                onLogout = {},
                onLogoutAll = {},
                onBack = {},
            )
        }

        compose.onNodeWithText("Szolgálati azonosító: SZ-408468(Régi)").assertIsDisplayed()
        compose.onAllNodesWithText("Becenév").onLast().performTextClearance()
        compose.onAllNodesWithText("Becenév").onLast().performTextInput("Levente")
        compose.onNodeWithText("Becenév mentése").performClick()

        assertEquals("Levente", submitted)
    }

    @Test
    fun moderator_own_profile_has_no_nickname_editor() {
        compose.setContent {
            AccountScreen(
                serviceId = "SZ-408468",
                nickname = null,
                role = "MODERATOR",
                busy = false,
                error = null,
                onChangePassword = { _, _, _ -> },
                onChangeNickname = { _, _ -> error("must not be reachable") },
                onLogout = {},
                onLogoutAll = {},
                onBack = {},
            )
        }

        assertTrue(compose.onAllNodesWithText("Becenév mentése").fetchSemanticsNodes().isEmpty())
    }
}
