package hu.orszembejelento.backend.reference.domain

/**
 * The offline-built canonical reference dataset, parsed from `manifest.json` and its three
 * CSV files. See `reference-data/README.md` and ADR 0006.
 *
 * This is a pure data holder — no file access, no database access. Loading and structural
 * validation live in the infrastructure layer; this type is what a validated load produces.
 */
data class CanonicalDataset(
    val manifest: DatasetManifest,
    val settlements: List<CandidateSettlement>,
    val railwayLines: List<CandidateRailwayLine>,
    val relations: List<CandidateRelation>,
)

enum class VerificationStatus { VERIFIED, UNVERIFIED }

enum class CoverageStatus { COMPLETE, PARTIAL }

/**
 * Whether reuse/redistribution rights for this dataset are cleared, independent of whether
 * its content is correct.
 *
 * A dataset can be exactly right (VERIFIED) and still not be ours to redistribute outside
 * the running service — see ADR 0006. PENDING is the default until a source issues an
 * explicit written reuse grant; the importer refuses to import a PENDING dataset.
 */
enum class ReuseStatus { PENDING, CLEARED }

data class DatasetManifest(
    val datasetVersion: String,
    val verificationStatus: VerificationStatus,
    val coverageStatus: CoverageStatus,
    val reuseStatus: ReuseStatus,
    val canonicalFileChecksums: Map<String, String>,
    val settlementCount: Int,
    val railwayLineCount: Int,
    val mappingCount: Int,
    val settlementsWithVerifiedRelations: Int,
    /** The raw parsed manifest, kept only to store as import provenance. Never mutated. */
    val rawJson: String,
)

data class CandidateSettlement(
    val kshCode: KshCode,
    val name: String,
    val countyName: String?,
)

data class CandidateRailwayLine(
    val lineCode: String,
    val displayName: String,
)

data class CandidateRelation(
    val kshCode: KshCode,
    val lineCode: String,
)
