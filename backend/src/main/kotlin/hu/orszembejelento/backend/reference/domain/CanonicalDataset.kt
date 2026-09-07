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
 * A per-component coverage claim: does this roster/mapping include everything, or only
 * what could be verified so far?
 *
 * "PARTIAL" means a different thing depending on which component it describes, which is
 * exactly why coverage cannot be one blanket flag - see [DatasetCoverage].
 */
enum class CoverageComponentStatus { COMPLETE, PARTIAL }

/**
 * Coverage tracked separately per component, because absence means something different in
 * each roster:
 *
 * - [settlements]: does this dataset list every settlement KSH recognises? For the real
 *   dataset this is COMPLETE - KSH publishes the full registry.
 * - [railwayLines]: does this dataset list every railway line in the network? Currently
 *   PARTIAL - the HÜSZ annexes were extracted with a deterministic parser and quarantine
 *   step that does not claim network-wide completeness.
 * - [settlementRailwayLines]: does this dataset capture every settlement<->line relation
 *   that exists? Currently PARTIAL - the annexes enumerate service points, not every
 *   settlement a line crosses (see `PHASE_3B_DECISION_GATE.md` SS7).
 *
 * The importer (ADR 0006) uses these, not the blanket [CoverageStatus], to decide whether a
 * row's absence from a newer dataset is evidence it should be deactivated/removed, or
 * merely evidence the dataset does not (yet) know about it. Absence under a COMPLETE
 * component may be treated as removal; absence under a PARTIAL component must be preserved
 * - anything else would let incomplete data assert a negative fact it cannot support.
 */
data class DatasetCoverage(
    val settlements: CoverageComponentStatus,
    val railwayLines: CoverageComponentStatus,
    val settlementRailwayLines: CoverageComponentStatus,
)

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
    val coverage: DatasetCoverage,
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
