package hu.orszembejelento.app.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import hu.orszembejelento.app.report.data.local.ReportHistoryEntity
import hu.orszembejelento.app.report.domain.SubmissionState
import java.time.Instant
import java.util.UUID
import org.junit.Rule
import org.junit.Test

/**
 * Phase 15 bonus finding: at increased font scale, [HistoryItemCard]'s title `Text` had no
 * `weight()` and claimed the row's width first, squeezing the status pill down until its own
 * short label ("Beérkezett") wrapped mid-word inside what should have been a one-line pill.
 * The title now shares the row via `weight(1f, fill = false)` and wraps onto a second line
 * itself instead, so the pill always keeps the room it needs. This is reproducible even at the
 * default density with a long enough title, so it needs no font-scale simulation.
 */
class HistoryItemCardComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entity(eventTypeDisplaySnapshot: String) = ReportHistoryEntity(
        clientSubmissionId = UUID.randomUUID(),
        publicReportId = UUID.randomUUID(),
        encryptedCredentialBlob = ByteArray(0),
        credentialCryptoVersion = ReportHistoryEntity.CURRENT_CRYPTO_VERSION,
        occurredAt = Instant.parse("2026-09-18T13:14:00Z"),
        trainIdentifier = null,
        settlementId = UUID.randomUUID(),
        settlementNameSnapshot = "Mintaváros",
        railwayLineId = null,
        railwayLineDisplaySnapshot = null,
        categoryDisplaySnapshot = "Rendzavarás és zaklatás",
        eventTypeCode = "PERSONNEL_ATTACKED",
        eventTypeDisplaySnapshot = eventTypeDisplaySnapshot,
        submissionState = SubmissionState.SUBMITTED,
        publicStatus = hu.orszembejelento.app.report.domain.PublicReportStatus.RECEIVED,
        serverSubmittedAt = Instant.parse("2026-09-18T13:14:05Z"),
        localCreatedAt = Instant.parse("2026-09-18T13:14:00Z"),
        lastStatusCheckedAt = Instant.parse("2026-09-18T13:28:00Z"),
        lastErrorCode = null,
    )

    @Test
    fun a_long_event_type_title_never_squeezes_the_status_pill_onto_multiple_lines() {
        compose.setContent {
            HistoryItemCard(
                item = entity("Egy nagyon hosszú, több szót tartalmazó esemény-típus név a kártyán"),
                onRetry = {},
                onRefresh = {},
            )
        }

        // A single line of `labelLarge` text is comfortably under 24dp tall. If the title
        // squeezes the pill's available width, "Beérkezett" wraps onto three lines instead
        // (the exact bug this test guards against) and would measure roughly 3x as tall.
        val bounds = compose.onNodeWithText("Beérkezett").assertIsDisplayed().getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("expected a one-line pill height, was $height", height < 24.dp)
    }

    @Test
    fun the_status_pill_stays_displayed_alongside_a_short_title_too() {
        compose.setContent {
            HistoryItemCard(item = entity("Rövid cím"), onRetry = {}, onRefresh = {})
        }

        compose.onNodeWithText("Rövid cím").assertIsDisplayed()
        compose.onNodeWithText("Beérkezett").assertIsDisplayed()
    }

    // ------------------------------------------------ 429 (B6): the report stays PENDING, retry is manual

    private fun pending(lastErrorCode: String?) = entity("Verekedés").copy(
        publicReportId = null,
        submissionState = SubmissionState.PENDING,
        publicStatus = null,
        serverSubmittedAt = null,
        lastStatusCheckedAt = null,
        lastErrorCode = lastErrorCode,
    )

    private val rateLimitedMessage =
        "Túl sok bejelentés érkezett rövid időn belül. Kérjük, várjon egy kicsit, majd próbálja újra."

    @Test
    fun a_throttled_pending_report_explains_why_and_still_offers_a_manual_retry() {
        var retried = 0
        compose.setContent {
            HistoryItemCard(item = pending("RATE_LIMITED"), onRetry = { retried++ }, onRefresh = {})
        }

        compose.onNodeWithText(rateLimitedMessage).assertIsDisplayed()
        compose.onNodeWithText("Újrapróbálás").assertIsDisplayed().performClick()
        assertTrue("the retry button must invoke the manual retry exactly once", retried == 1)
    }

    @Test
    fun the_throttling_message_never_appears_on_other_pending_reports() {
        compose.setContent {
            HistoryItemCard(item = pending("NETWORK_OR_OTHER"), onRetry = {}, onRefresh = {})
        }

        compose.onNodeWithText(rateLimitedMessage).assertDoesNotExist()
        compose.onNodeWithText("Újrapróbálás").assertIsDisplayed()
    }

    @Test
    fun the_throttling_message_does_not_blame_the_person() {
        val lower = rateLimitedMessage.lowercase()
        listOf("hibáz", "rosszul", "gyanús", "visszaél", "spam", "tiltott").forEach {
            assertTrue("copy must not blame the user: contains '$it'", !lower.contains(it))
        }
    }
}
