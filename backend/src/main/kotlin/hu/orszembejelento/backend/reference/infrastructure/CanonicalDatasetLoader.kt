package hu.orszembejelento.backend.reference.infrastructure

import hu.orszembejelento.backend.reference.domain.CandidateRailwayLine
import hu.orszembejelento.backend.reference.domain.CandidateRelation
import hu.orszembejelento.backend.reference.domain.CandidateSettlement
import hu.orszembejelento.backend.reference.domain.CanonicalDataset
import hu.orszembejelento.backend.reference.domain.CoverageComponentStatus
import hu.orszembejelento.backend.reference.domain.CoverageStatus
import hu.orszembejelento.backend.reference.domain.DatasetCoverage
import hu.orszembejelento.backend.reference.domain.DatasetManifest
import hu.orszembejelento.backend.reference.domain.KshCode
import hu.orszembejelento.backend.reference.domain.ReuseStatus
import hu.orszembejelento.backend.reference.domain.VerificationStatus
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Loads and structurally validates a canonical reference dataset from a directory.
 *
 * This is the same set of checks `reference-data/tools/validate-canonical.mjs` runs
 * offline in CI, re-implemented here so the backend's `validate`/`diff`/`import` commands
 * never depend on Node being installed on the server. Both tools must keep agreeing, which
 * is exactly why the checks are this literal and this narrow.
 *
 * Returns either a fully valid [CanonicalDataset] or a list of every issue found — never a
 * partially-trusted result silently used by a caller.
 */
@Component
class CanonicalDatasetLoader(private val objectMapper: ObjectMapper) {

    sealed class LoadResult {
        data class Valid(val dataset: CanonicalDataset) : LoadResult()
        data class Invalid(val issues: List<String>) : LoadResult()
    }

    fun load(directory: Path): LoadResult {
        val issues = mutableListOf<String>()

        val manifestFile = directory.resolve("manifest.json")
        if (!Files.isRegularFile(manifestFile)) {
            return LoadResult.Invalid(listOf("manifest.json not found in $directory"))
        }
        val manifestJsonText = String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8)
        val manifestNode = try {
            objectMapper.readTree(manifestJsonText)
        } catch (e: Exception) {
            return LoadResult.Invalid(listOf("manifest.json is not valid JSON: ${e.message}"))
        }

        val verificationStatus = enumOrIssue<VerificationStatus>(manifestNode, "verificationStatus", issues)
        val coverageStatus = enumOrIssue<CoverageStatus>(manifestNode, "coverageStatus", issues)
        val reuseStatus = enumOrIssue<ReuseStatus>(manifestNode, "reuseStatus", issues)
        val datasetVersion = manifestNode.path("datasetVersion").asString(null)
        if (datasetVersion.isNullOrBlank()) issues += "datasetVersion is missing"

        // Coverage is tracked per component (ADR 0006), not as one blanket flag: absence of
        // a row means something different depending on which roster is incomplete.
        val coverageNode = manifestNode.path("coverage")
        val settlementsCoverage = enumOrIssue<CoverageComponentStatus>(coverageNode, "settlements", issues)
        val railwayLinesCoverage = enumOrIssue<CoverageComponentStatus>(coverageNode, "railwayLines", issues)
        val relationsCoverage = enumOrIssue<CoverageComponentStatus>(coverageNode, "settlementRailwayLines", issues)

        val canonicalFilesNode = manifestNode.path("canonicalFiles")
        val checksums = mutableMapOf<String, String>()
        canonicalFilesNode.propertyNames().forEach { name ->
            checksums[name] = canonicalFilesNode.path(name).asString("")
        }

        // -------------------------------------------------------------- checksums
        for ((fileName, expectedSha256) in checksums) {
            val file = directory.resolve(fileName)
            if (!Files.isRegularFile(file)) {
                issues += "canonical file missing: $fileName"
                continue
            }
            val actual = sha256Hex(file)
            if (actual != expectedSha256) {
                issues += "$fileName checksum mismatch (manifest: $expectedSha256, actual: $actual)"
            }
        }

        // If the fundamentals are already broken, there is no safe further parsing to do.
        if (verificationStatus == null || coverageStatus == null || reuseStatus == null ||
            settlementsCoverage == null || railwayLinesCoverage == null || relationsCoverage == null ||
            datasetVersion.isNullOrBlank() || issues.isNotEmpty()
        ) {
            return LoadResult.Invalid(issues.ifEmpty { listOf("manifest.json is missing required fields") })
        }

        // The blanket status must not overclaim relative to its own components: a manifest
        // that says COMPLETE overall while one component is PARTIAL is self-contradictory.
        if (coverageStatus == CoverageStatus.COMPLETE &&
            (settlementsCoverage == CoverageComponentStatus.PARTIAL ||
                railwayLinesCoverage == CoverageComponentStatus.PARTIAL ||
                relationsCoverage == CoverageComponentStatus.PARTIAL)
        ) {
            issues += "coverageStatus is COMPLETE but at least one coverage component is PARTIAL"
            return LoadResult.Invalid(issues)
        }

        val settlements = readCsv(
            directory.resolve("settlements.csv"),
            listOf("ksh_code", "name", "county_name"),
            issues,
        )
        val lines = readCsv(
            directory.resolve("railway-lines.csv"),
            listOf("line_code", "display_name"),
            issues,
        )
        val relations = readCsv(
            directory.resolve("settlement-railway-lines.csv"),
            listOf("ksh_code", "line_code"),
            issues,
        )

        if (issues.isNotEmpty()) return LoadResult.Invalid(issues)

        // -------------------------------------------------------------- settlements
        val kshCodesSeen = mutableSetOf<String>()
        val candidateSettlements = mutableListOf<CandidateSettlement>()
        for (row in settlements) {
            val (code, name, county) = row
            val kshCode = KshCode.parseOrNull(code)
            if (kshCode == null) {
                issues += "settlement code is not five digits: '$code'"
                continue
            }
            if (name.isBlank()) issues += "settlement $code has no name"
            if (!kshCodesSeen.add(code)) issues += "duplicate settlement code: $code"
            candidateSettlements += CandidateSettlement(kshCode, name, county.ifBlank { null })
        }

        // -------------------------------------------------------------- railway lines
        val lineCodesSeen = mutableSetOf<String>()
        val candidateLines = mutableListOf<CandidateRailwayLine>()
        for (row in lines) {
            val (code, displayName) = row
            if (code.isBlank()) issues += "railway line with empty code"
            if (displayName.isBlank()) issues += "railway line $code has no display name"
            if (!lineCodesSeen.add(code)) issues += "duplicate line code: $code"
            candidateLines += CandidateRailwayLine(code, displayName)
        }

        // -------------------------------------------------------------- relations
        val seenRelationKeys = mutableSetOf<Pair<String, String>>()
        val candidateRelations = mutableListOf<CandidateRelation>()
        for (row in relations) {
            val (kshValue, lineCode) = row
            val key = kshValue to lineCode
            if (!seenRelationKeys.add(key)) {
                issues += "duplicate relation: $kshValue|$lineCode"
                continue
            }
            if (kshValue !in kshCodesSeen) issues += "relation references unknown settlement: $kshValue"
            if (lineCode !in lineCodesSeen) issues += "relation references unknown line: $lineCode"
            val kshCode = KshCode.parseOrNull(kshValue) ?: continue
            candidateRelations += CandidateRelation(kshCode, lineCode)
        }

        // -------------------------------------------------------------- count cross-checks
        val counts = manifestNode.path("counts")
        checkCount(counts, "settlements", candidateSettlements.size, issues)
        checkCount(counts, "railwayLines", candidateLines.size, issues)
        checkCount(counts, "settlementRailwayLineMappings", candidateRelations.size, issues)

        val coveredSettlements = candidateRelations.map { it.kshCode.value }.toSet()
        if (coverageNode.has("settlementsWithVerifiedRelations")) {
            val claimed = coverageNode.path("settlementsWithVerifiedRelations").asInt(-1)
            if (claimed != coveredSettlements.size) {
                issues += "manifest coverage says $claimed settlements with relations, " +
                    "data has ${coveredSettlements.size}"
            }
        }

        // There is deliberately no "coveredSettlements < total settlements => reject
        // COMPLETE" check here. That would assume nearly every settlement has a railway
        // line, which is false - most genuinely do not, no matter how complete the mapping
        // is. The real structural guard is the component/overall consistency check above:
        // an overall COMPLETE claim may not contradict a PARTIAL component.

        if (issues.isNotEmpty()) return LoadResult.Invalid(issues)

        val manifest = DatasetManifest(
            datasetVersion = datasetVersion,
            verificationStatus = verificationStatus,
            coverageStatus = coverageStatus,
            coverage = DatasetCoverage(settlementsCoverage, railwayLinesCoverage, relationsCoverage),
            reuseStatus = reuseStatus,
            canonicalFileChecksums = checksums,
            settlementCount = candidateSettlements.size,
            railwayLineCount = candidateLines.size,
            mappingCount = candidateRelations.size,
            settlementsWithVerifiedRelations = coveredSettlements.size,
            rawJson = manifestJsonText,
        )

        return LoadResult.Valid(
            CanonicalDataset(manifest, candidateSettlements, candidateLines, candidateRelations),
        )
    }

    /** SHA-256 of the whole committed manifest.json, used as the provenance/idempotency key. */
    fun manifestSha256(directory: Path): ByteArray = sha256Bytes(directory.resolve("manifest.json"))

    private fun checkCount(countsNode: JsonNode, field: String, actual: Int, issues: MutableList<String>) {
        if (!countsNode.has(field)) return
        val claimed = countsNode.path(field).asInt(-1)
        if (claimed != actual) issues += "manifest counts.$field says $claimed, data has $actual"
    }

    private inline fun <reified E : Enum<E>> enumOrIssue(
        node: JsonNode,
        field: String,
        issues: MutableList<String>,
    ): E? {
        val raw = node.path(field).asString(null)
        val value = raw?.let { runCatching { enumValueOf<E>(it) }.getOrNull() }
        if (value == null) {
            val allowed = enumValues<E>().joinToString(" or ")
            issues += "$field must be $allowed, got ${raw ?: "<missing>"}"
        }
        return value
    }

    /**
     * Reads a CSV file, enforcing exactly the constraints the importer depends on: valid
     * UTF-8, no BOM, and the exact expected header. A file that fails these is unsafe to
     * parse further, so callers see it as an issue rather than best-effort rows.
     */
    private fun readCsv(file: Path, expectedHeader: List<String>, issues: MutableList<String>): List<List<String>> {
        if (!Files.isRegularFile(file)) {
            issues += "${file.fileName} not found"
            return emptyList()
        }
        val bytes = Files.readAllBytes(file)
        val text = try {
            strictUtf8Decode(bytes)
        } catch (e: CharacterCodingException) {
            issues += "${file.fileName} is not valid UTF-8"
            return emptyList()
        }
        if (text.isNotEmpty() && text[0].code == 0xFEFF) {
            issues += "${file.fileName} starts with a BOM"
            return emptyList()
        }

        val lines = text.split("\n").let { all ->
            // Drop a single trailing empty line from the final newline, keep genuine blanks.
            if (all.isNotEmpty() && all.last().isEmpty()) all.dropLast(1) else all
        }
        if (lines.isEmpty()) {
            issues += "${file.fileName} has no header row"
            return emptyList()
        }
        val header = splitCsvLine(lines[0].removeSuffix("\r"))
        if (header != expectedHeader) {
            issues += "${file.fileName} header is ${header.joinToString(",")}, expected ${expectedHeader.joinToString(",")}"
            return emptyList()
        }
        return lines.drop(1).filter { it.isNotEmpty() }.map { splitCsvLine(it.removeSuffix("\r")) }
    }

    private fun strictUtf8Decode(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /** Mirrors the quoting rules of the offline JS validator/builder exactly. */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (quoted) {
                when {
                    ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                    ch == '"' -> quoted = false
                    else -> cur.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    ',' -> { out += cur.toString(); cur.setLength(0) }
                    else -> cur.append(ch)
                }
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun sha256Hex(file: Path): String = sha256Bytes(file).joinToString("") { "%02x".format(it) }

    private fun sha256Bytes(file: Path): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
}
