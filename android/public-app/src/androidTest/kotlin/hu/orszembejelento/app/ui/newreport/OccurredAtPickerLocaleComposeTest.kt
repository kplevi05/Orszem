package hu.orszembejelento.app.ui.newreport

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Instant
import org.junit.Rule
import org.junit.Test

/**
 * Phase 15: Material's date/time pickers draw their own text (title, headline, month and
 * weekday names, and every accessibility label) from the device locale. The Public app is
 * Hungarian, so the picker subtree is given a hu-HU configuration inside the dialog window.
 * These tests open the real dialogs and assert Hungarian text AND accessibility labels are
 * present and the English ones absent. They are meaningful on an English-locale device (the
 * emulator used here is en-US); on a Hungarian-locale device they would pass regardless.
 */
class OccurredAtPickerLocaleComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent() {
        compose.setContent { OccurredAtPicker(value = Instant.parse("2026-09-16T11:40:00Z"), onValueChange = {}) }
    }

    @Test
    fun the_date_picker_shows_hungarian_title_and_accessibility_labels_not_english() {
        setContent()
        compose.onAllNodes(hasClickAction())[0].performClick()

        compose.onNodeWithText("Dátum kiválasztása").assertExists()
        compose.onNodeWithText("Rendben").assertExists()
        compose.onNodeWithText("Mégse").assertExists()
        compose.onNodeWithContentDescription("Váltás a következő hónapra").assertExists()
        compose.onNodeWithContentDescription("Váltás az előző hónapra").assertExists()
        compose.onNodeWithContentDescription("Váltás az év kiválasztására").assertExists()

        compose.onAllNodesWithText("Select date").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Change to next month").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Change to previous month").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Switch to selecting a year").assertCountEquals(0)
    }

    @Test
    fun the_date_picker_names_days_and_months_in_hungarian() {
        setContent()
        compose.onAllNodes(hasClickAction())[0].performClick()

        // The selected day is described in full Hungarian in the semantics tree.
        compose.onNodeWithContentDescription("Jelenleg kiválasztva: 2026. szeptember 16., szerda", substring = false).assertExists()
        compose.onAllNodes(hasContentDescription("Wednesday", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("September", substring = true)).assertCountEquals(0)
    }

    @Test
    fun the_time_picker_labels_are_hungarian_not_english() {
        setContent()
        compose.onAllNodes(hasClickAction())[1].performClick()

        compose.onNodeWithContentDescription("Óra kiválasztása").assertExists()
        compose.onNodeWithContentDescription("Perc kiválasztása").assertExists()
        compose.onNodeWithText("Rendben").assertExists()
        compose.onNodeWithText("Mégse").assertExists()

        compose.onAllNodesWithContentDescription("Select hour").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Select minutes").assertCountEquals(0)
    }
}
