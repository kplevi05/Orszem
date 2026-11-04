package hu.orszembejelento.backend.reportworkflow.infrastructure

import hu.orszembejelento.backend.reportworkflow.domain.ReportMessage
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcReportMessageRepository(private val jdbc: JdbcClient) {

    fun insertMessage(
        reportId: UUID,
        senderUserId: UUID,
        messageText: String,
        sentAt: Instant,
    ): ReportMessage {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            INSERT INTO report_messages (id, report_id, sender_user_id, message_text, sent_at)
            VALUES (:id, :reportId, :senderUserId, :messageText, :sentAt)
            """.trimIndent(),
        )
            .param("id", id)
            .param("reportId", reportId)
            .param("senderUserId", senderUserId)
            .param("messageText", messageText)
            .param("sentAt", Timestamp.from(sentAt))
            .update()

        return ReportMessage(
            id = id,
            reportId = reportId,
            senderUserId = senderUserId,
            messageText = messageText,
            sentAt = sentAt,
        )
    }

    fun findMessagesByReport(reportId: UUID): List<ReportMessage> =
        jdbc.sql(
            """
            SELECT id, report_id, sender_user_id, message_text, sent_at
              FROM report_messages
             WHERE report_id = :reportId
             ORDER BY sent_at ASC, id ASC
            """.trimIndent(),
        )
            .param("reportId", reportId)
            .query(::mapMessage)
            .list()

    private fun mapMessage(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ReportMessage(
        id = rs.getObject("id", UUID::class.java),
        reportId = rs.getObject("report_id", UUID::class.java),
        senderUserId = rs.getObject("sender_user_id", UUID::class.java),
        messageText = rs.getString("message_text"),
        sentAt = rs.getTimestamp("sent_at").toInstant(),
    )
}
