package hu.orszembejelento.backend.reference.domain

/** Base type for every way a reference dataset import can be refused. Each carries a stable [code]. */
sealed class ReferenceImportException(val code: String, message: String) : RuntimeException(message)

/** The dataset directory is missing, malformed, or fails a structural/content check. */
class ReferenceDatasetInvalidException(val issues: List<String>) : ReferenceImportException(
    "REFERENCE_DATASET_INVALID",
    "the dataset failed ${issues.size} validation check(s):\n" + issues.joinToString("\n") { "  - $it" },
)

/** `verificationStatus` is not VERIFIED. Import never proceeds on an unverified dataset. */
class ReferenceDatasetNotVerifiedException(actual: VerificationStatus) : ReferenceImportException(
    "REFERENCE_DATASET_NOT_VERIFIED",
    "refusing to import: verificationStatus is $actual, not VERIFIED",
)

/**
 * `reuseStatus` is not CLEARED. Import never proceeds on a dataset whose reuse rights are
 * still pending, regardless of how sound its correctness is. See ADR 0006. There is no
 * bypass flag by design.
 */
class ReferenceDatasetReuseNotClearedException(actual: ReuseStatus) : ReferenceImportException(
    "REFERENCE_DATASET_REUSE_NOT_CLEARED",
    "refusing to import: reuseStatus is $actual, not CLEARED " +
        "(see ADR 0006 - obtain written confirmation, then set reuseStatus: CLEARED)",
)

/**
 * A dataset with this [datasetVersion] was already imported under a different manifest.
 * Re-importing the identical content under the same version is idempotent (a no-op); this
 * is refused only when the content actually differs, so provenance is never silently
 * rewritten to describe something else.
 */
class ReferenceDatasetVersionConflictException(val datasetVersion: String) : ReferenceImportException(
    "REFERENCE_DATASET_VERSION_CONFLICT",
    "dataset version '$datasetVersion' was already imported with a different manifest; " +
        "a version must not be reused for different content",
)

/**
 * The new dataset no longer lists one or more railway lines that are currently assigned to a
 * service area. Deactivating them would silently orphan that operational configuration, so
 * the whole import is refused instead - see the service-area routing model in ADR 0005.
 */
class ReferenceLineInUseException(val lineCodes: Set<String>) : ReferenceImportException(
    "REFERENCE_LINE_IN_USE",
    "refusing to import: ${lineCodes.size} railway line(s) would be deactivated but are " +
        "still assigned to a service area: ${lineCodes.sorted().joinToString(", ")}",
)
