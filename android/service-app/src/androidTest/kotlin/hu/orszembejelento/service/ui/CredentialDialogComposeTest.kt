package hu.orszembejelento.service.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hu.orszembejelento.service.R
import hu.orszembejelento.service.usermanagement.ui.CredentialDisplayDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * brief §56-57 / §99: the one-time temporary credential is shown once, only while the dialog
 * is open. Dismissing it removes the plaintext from the UI; there is no persistence path it
 * could come back from (that is covered structurally by the ViewModel tests - here we prove
 * the dialog itself holds nothing after dismissal).
 */
class CredentialDialogComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun the_credential_is_visible_while_open_and_gone_after_saved_it() {
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            if (visible) {
                CredentialDisplayDialog(
                    titleRes = R.string.credential_title,
                    serviceId = "SZ-424242",
                    temporaryCredential = "ABCD-2345-EFGH-6789",
                    onDismiss = { visible = false },
                )
            }
        }

        compose.onNodeWithText("ABCD-2345-EFGH-6789").assertExists()
        compose.onNodeWithText(
            "Az ideiglenes jelszó csak most látható. Mentse el biztonságosan, mielőtt bezárja ezt az ablakot.",
        ).assertExists()

        compose.onNodeWithText("Elmentettem").performClick()
        compose.waitForIdle()

        assertTrue(
            "the plaintext credential must not remain anywhere in the UI after dismissal",
            compose.onAllNodesWithText("ABCD-2345-EFGH-6789", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}
