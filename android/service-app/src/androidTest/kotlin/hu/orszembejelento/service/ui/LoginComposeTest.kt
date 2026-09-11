package hu.orszembejelento.service.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import hu.orszembejelento.service.auth.ui.LoginScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * brief §7-8/§95: the production login is Service ID + password + one button. No demo
 * account picker, no client-side role selector, no environment/token text.
 */
class LoginComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shows_only_service_id_password_and_a_single_login_action() {
        var submitted: Pair<String, String>? = null
        compose.setContent { LoginScreen(busy = false, error = null, onLogin = { id, pw -> submitted = id to pw }) }

        compose.onNodeWithText("Szolgálati azonosító").assertIsDisplayed()
        compose.onNodeWithText("Jelszó").assertIsDisplayed()

        compose.onNodeWithText("Szolgálati azonosító").performTextInput("SZ-123456")
        compose.onNodeWithText("Jelszó").performTextInput("nyitni tessek most rogton")
        // "Bejelentkezés" is both the screen title and the button; the button is the last one.
        compose.onAllNodesWithText("Bejelentkezés").onLast().performClick()

        assertEquals("SZ-123456" to "nyitni tessek most rogton", submitted)
    }

    @Test
    fun has_no_demo_account_picker_or_role_selector() {
        compose.setContent { LoginScreen(busy = false, error = null, onLogin = { _, _ -> }) }

        listOf("SERVICE_USER", "MODERATOR", "SUPER_ADMIN", "demo", "Demo", "DEMO", "próba", "teszt fiók")
            .forEach { forbidden ->
                assertTrue(
                    "the login screen must not surface \"$forbidden\"",
                    compose.onAllNodesWithText(forbidden, substring = true).fetchSemanticsNodes().isEmpty(),
                )
            }
    }
}
