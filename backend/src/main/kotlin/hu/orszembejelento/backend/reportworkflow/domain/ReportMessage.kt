package hu.orszembejelento.backend.reportworkflow.domain

import java.time.Instant
import java.util.UUID

/**
 * One immutable, append-only message sent by a service user to a report's anonymous reporter.
 * Once persisted, it can never be edited or withdrawn.
 */
data class ReportMessage(
    val id: UUID,
    val reportId: UUID,
    val senderUserId: UUID,
    val messageText: String,
    val sentAt: Instant,
) {
    companion object {
        const val MAX_CODE_POINTS = 1000

        fun normalizeText(raw: String?): String {
            val trimmed = raw?.replace("\r\n", "\n")?.replace("\r", "\n")?.trim()
                ?: throw ReportMessageInvalidException()
            if (trimmed.isEmpty()) throw ReportMessageInvalidException()
            if (trimmed.codePointCount(0, trimmed.length) > MAX_CODE_POINTS) {
                throw ReportMessageInvalidException()
            }
            if (containsInvalidPgChars(trimmed)) {
                throw ReportMessageInvalidException()
            }
            return trimmed
        }

        private fun containsInvalidPgChars(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\u0000') return true
                if (c.isHighSurrogate()) {
                    if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
                    i += 2
                    continue
                }
                if (c.isLowSurrogate()) return true
                i++
            }
            return false
        }
    }
}
