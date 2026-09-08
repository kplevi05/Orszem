package hu.orszembejelento.backend.common

/**
 * The single PostgreSQL advisory-lock identity guarding transitions of the current
 * reference state (ADR 0007) against report submissions that read it (ADR 0008).
 *
 * Used two ways on the *same* key, never two different keys:
 *  - a reference import holds the **exclusive** lock
 *    ([hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository.acquireImportLock])
 *    for its whole transaction while it changes what is current;
 *  - a report submission holds the **shared** lock
 *    ([hu.orszembejelento.backend.reference.infrastructure.JdbcReferenceRepository.acquireSharedReferenceStateLock])
 *    for its whole transaction while it reads reference/service-area state and computes a
 *    routing snapshot.
 *
 * Many submissions may hold the shared lock concurrently. An import waits for all of them
 * to finish before it can start changing anything; a submission that begins while an
 * import is in flight waits for the import to finish first. Either way, a report's routing
 * snapshot is always computed against one consistent reference-state revision, never a mix
 * of what an in-flight import is removing and what it is adding.
 *
 * Centralised here specifically so the two call sites can never drift onto different key
 * values by accident.
 */
object ReferenceStateLock {
    // An arbitrary, never-reused constant. Picked once when the reference importer was
    // built (Phase 3B); changing it would only matter if something else in the schema also
    // took advisory locks, which nothing does.
    const val KEY = 7_281_004_419_887_233L
}
