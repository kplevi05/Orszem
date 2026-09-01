package hu.orszem.servicecase.infrastructure

import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Opaque keyset cursors. Clients must treat these as blobs and never parse them.
 * Internal layout: "type|field|field..." base64url-encoded.
 */
internal object ReportCursor {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private const val SEP = "|"

    data class Active(val isNew: Boolean, val receivedAt: Instant, val id: UUID)
    data class Archived(val archivedAt: Instant, val id: UUID)

    fun encodeActive(c: Active): String =
        encode("A", if (c.isNew) "1" else "0", c.receivedAt.toEpochMicros().toString(), c.id.toString())

    fun encodeArchived(c: Archived): String =
        encode("R", c.archivedAt.toEpochMicros().toString(), c.id.toString())

    fun decodeActive(cursor: String): Active? = runCatching {
        val parts = decode(cursor)
        require(parts.size == 4 && parts[0] == "A")
        Active(parts[1] == "1", instantFromMicros(parts[2].toLong()), UUID.fromString(parts[3]))
    }.getOrNull()

    fun decodeArchived(cursor: String): Archived? = runCatching {
        val parts = decode(cursor)
        require(parts.size == 3 && parts[0] == "R")
        Archived(instantFromMicros(parts[1].toLong()), UUID.fromString(parts[2]))
    }.getOrNull()

    private fun encode(vararg parts: String): String =
        encoder.encodeToString(parts.joinToString(SEP).toByteArray(Charsets.UTF_8))

    private fun decode(cursor: String): List<String> =
        String(decoder.decode(cursor), Charsets.UTF_8).split(SEP)

    private fun Instant.toEpochMicros(): Long = epochSecond * 1_000_000L + nano / 1_000L

    private fun instantFromMicros(micros: Long): Instant =
        Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)
}
