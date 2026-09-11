package hu.orszembejelento.service.reports.domain

import hu.orszembejelento.service.reports.data.AssignmentHistoryItemResponse

/** One human-readable timeline entry (brief §29) - never a raw backend field dump. */
data class AssignmentHistoryEntry(
    val label: String,
    val timestampRaw: String,
    val active: Boolean,
)

/**
 * Turns the backend's raw assignment episodes into the simple timeline the approved mockup
 * shows (brief §29): "SZ-1042 átvette", "SZ-2041 átrendezte SZ-1059 felhasználóhoz",
 * "SZ-1059 ügyintézése lezárult". Deliberately separate from the security audit trail - this
 * reads only `report_assignments` fields, nothing from `audit_events`.
 *
 * One entry per *transition*: an episode's start (self-claim if `assignedBy == assignee`,
 * otherwise a reassignment) is always shown; its end is shown only when it did **not** end
 * by being reassigned away, since that fact is already fully captured by the *next*
 * episode's own start line - showing both would say the same transition twice.
 */
fun buildAssignmentHistoryEntries(history: List<AssignmentHistoryItemResponse>): List<AssignmentHistoryEntry> =
    history.flatMap { episode ->
        buildList {
            add(
                if (episode.assignedByServiceId == episode.assigneeServiceId) {
                    AssignmentHistoryEntry(
                        label = "${episode.assigneeServiceId} átvette",
                        timestampRaw = episode.assignedAt,
                        active = episode.endedAt == null,
                    )
                } else {
                    AssignmentHistoryEntry(
                        label = "${episode.assignedByServiceId} átrendelte ${episode.assigneeServiceId} felhasználóhoz",
                        timestampRaw = episode.assignedAt,
                        active = false,
                    )
                },
            )
            if (episode.endedAt != null && episode.endReason != "REASSIGNED") {
                val endedBy = episode.endedByServiceId ?: episode.assigneeServiceId
                val label = when (episode.endReason) {
                    "RETURNED" -> "$endedBy visszaadta"
                    "ARCHIVED" -> "$endedBy lezárta"
                    // Phase 9 brief §52: a moderation deletion terminated this episode - never
                    // shown as the raw "MODERATION_DELETED" code. Deliberately the ASSIGNEE's
                    // own service ID here, not `endedBy` (the moderator who deleted it) - the
                    // sentence is about whose handling ended, not who ended it.
                    "MODERATION_DELETED" -> "${episode.assigneeServiceId} ügyintézése moderációs törlés miatt megszűnt"
                    // Every current end reason is handled above; any other value degrades to
                    // a neutral phrase rather than showing a raw code (brief §29/§85).
                    else -> "$endedBy lezárta az ügyintézést"
                }
                add(AssignmentHistoryEntry(label = label, timestampRaw = episode.endedAt, active = false))
            }
        }
    }
