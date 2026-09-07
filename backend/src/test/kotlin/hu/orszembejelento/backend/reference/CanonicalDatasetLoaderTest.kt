package hu.orszembejelento.backend.reference

import hu.orszembejelento.backend.reference.domain.CoverageComponentStatus
import hu.orszembejelento.backend.reference.domain.CoverageStatus
import hu.orszembejelento.backend.reference.domain.ReuseStatus
import hu.orszembejelento.backend.reference.domain.VerificationStatus
import hu.orszembejelento.backend.reference.infrastructure.CanonicalDatasetLoader
import hu.orszembejelento.backend.reference.support.ReferenceDatasetFixture
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper

/**
 * The structural/content checks a dataset must pass before it is even considered for
 * import - the same checks `reference-data/tools/validate-canonical.mjs` runs offline, so
 * this class exists to prove the two never quietly disagree.
 *
 * Pure JVM tests: no Spring context, no database. [JsonMapper] is instantiated directly.
 */
class CanonicalDatasetLoaderTest {

    private val loader = CanonicalDatasetLoader(JsonMapper.builder().build())

    @Test
    fun `a well-formed fixture loads as valid with every manifest field parsed`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(tmp, version = "1.0", reuse = "PENDING")

        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Valid
        val m = result.dataset.manifest
        check(m.datasetVersion == "1.0")
        check(m.verificationStatus == VerificationStatus.VERIFIED)
        // The fixture defaults every coverage component to COMPLETE, so the derived
        // blanket status is COMPLETE too.
        check(m.coverageStatus == CoverageStatus.COMPLETE)
        check(m.coverage.settlements == CoverageComponentStatus.COMPLETE)
        check(m.coverage.railwayLines == CoverageComponentStatus.COMPLETE)
        check(m.coverage.settlementRailwayLines == CoverageComponentStatus.COMPLETE)
        check(m.reuseStatus == ReuseStatus.PENDING)
        check(m.settlementCount == 3)
        check(m.railwayLineCount == 2)
        check(m.mappingCount == 4)
        check(result.dataset.settlements.size == 3)
        check(result.dataset.relations.size == 4)
    }

    @Test
    fun `missing manifest is invalid`(@TempDir tmp: Path) {
        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("manifest.json") })
    }

    @Test
    fun `a tampered canonical file fails its checksum`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(tmp, version = "1.0")
        Files.writeString(tmp.resolve("settlements.csv"), "ksh_code,name,county_name\n00001,Tampered,\n")

        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("checksum mismatch") })
    }

    @Test
    fun `an invalid verificationStatus value is rejected`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(tmp, version = "1.0", verification = "PROBABLY")
        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("verificationStatus") })
    }

    @Test
    fun `an invalid reuseStatus value is rejected`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(tmp, version = "1.0", reuse = "MAYBE")
        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("reuseStatus") })
    }

    @Test
    fun `an invalid coverage component value is rejected`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(tmp, version = "1.0", railwayLinesCoverage = "SORT-OF")
        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("railwayLines") && it.contains("SORT-OF") })
    }

    @Test
    fun `an overall COMPLETE claim contradicted by a PARTIAL component is rejected`(@TempDir tmp: Path) {
        // A hand-written manifest is needed here: ReferenceDatasetFixture always derives
        // the blanket status from its components, so it cannot express this contradiction.
        val dir = tmp
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("settlements.csv"), "ksh_code,name,county_name\n00001,Alfa,\n")
        Files.writeString(dir.resolve("railway-lines.csv"), "line_code,display_name\n")
        Files.writeString(dir.resolve("settlement-railway-lines.csv"), "ksh_code,line_code\n")
        val manifest = """
            {
              "datasetVersion": "1.0",
              "verificationStatus": "VERIFIED",
              "coverageStatus": "COMPLETE",
              "reuseStatus": "CLEARED",
              "sources": {},
              "canonicalFiles": {
                "settlements.csv": "${ReferenceDatasetFixture.sha256Hex(dir.resolve("settlements.csv"))}",
                "railway-lines.csv": "${ReferenceDatasetFixture.sha256Hex(dir.resolve("railway-lines.csv"))}",
                "settlement-railway-lines.csv": "${ReferenceDatasetFixture.sha256Hex(dir.resolve("settlement-railway-lines.csv"))}"
              },
              "counts": { "settlements": 1, "railwayLines": 0, "settlementRailwayLineMappings": 0 },
              "coverage": {
                "settlements": "COMPLETE",
                "railwayLines": "PARTIAL",
                "settlementRailwayLines": "COMPLETE",
                "settlementsWithVerifiedRelations": 0
              }
            }
        """.trimIndent()
        Files.writeString(dir.resolve("manifest.json"), manifest)

        val result = loader.load(dir) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("coverageStatus is COMPLETE") })
    }

    @Test
    fun `a relation referencing an unknown settlement is rejected`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(
            tmp,
            version = "1.0",
            settlements = listOf(Triple("00001", "Alfa", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00099" to "1"), // 00099 does not exist
            countsOverride = Triple(1, 1, 1),
        )
        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("unknown settlement") })
    }

    @Test
    fun `a relation referencing an unknown line is rejected`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(
            tmp,
            version = "1.0",
            settlements = listOf(Triple("00001", "Alfa", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "9"), // line 9 does not exist
            countsOverride = Triple(1, 1, 1),
        )
        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("unknown line") })
    }

    @Test
    fun `duplicate settlement codes are rejected`(@TempDir tmp: Path) {
        val dir = tmp
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("settlements.csv"),
            "ksh_code,name,county_name\n00001,Alfa,\n00001,Alfa Again,\n",
        )
        Files.writeString(dir.resolve("railway-lines.csv"), "line_code,display_name\n")
        Files.writeString(dir.resolve("settlement-railway-lines.csv"), "ksh_code,line_code\n")
        writeManifestFor(dir, version = "1.0", settlementCount = 2, lineCount = 0, mappingCount = 0)

        val result = loader.load(dir) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("duplicate settlement code") })
    }

    @Test
    fun `a settlement code that is not five digits is rejected`(@TempDir tmp: Path) {
        val dir = tmp
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("settlements.csv"), "ksh_code,name,county_name\n123,Alfa,\n")
        Files.writeString(dir.resolve("railway-lines.csv"), "line_code,display_name\n")
        Files.writeString(dir.resolve("settlement-railway-lines.csv"), "ksh_code,line_code\n")
        writeManifestFor(dir, version = "1.0", settlementCount = 1, lineCount = 0, mappingCount = 0)

        val result = loader.load(dir) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("not five digits") })
    }

    @Test
    fun `a byte-order mark at the start of a csv is rejected`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(tmp, version = "1.0")
        val withBom = "﻿" + Files.readString(tmp.resolve("settlements.csv"))
        Files.write(tmp.resolve("settlements.csv"), withBom.toByteArray(StandardCharsets.UTF_8))
        // Re-point the manifest checksum at the tampered file so this test isolates the BOM
        // check rather than tripping the (already-covered) checksum check first.
        rewriteChecksum(tmp, "settlements.csv")

        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("BOM") })
    }

    @Test
    fun `a COMPLETE relation mapping is accepted even though most settlements have no relation`(@TempDir tmp: Path) {
        // Not a bug: most Hungarian settlements genuinely have no railway line, so a
        // correctly COMPLETE relation mapping is still expected to leave most settlements
        // uncovered. There is no "covered < total" heuristic here (see the loader comment
        // and ADR 0006) - only the component/overall consistency check applies.
        ReferenceDatasetFixture.write(
            tmp,
            version = "1.0",
            settlements = listOf(Triple("00001", "Alfa", null), Triple("00002", "Beta", null)),
            lines = listOf("1" to "Line 1"),
            relations = listOf("00001" to "1"), // 00002 genuinely has no railway
            countsOverride = Triple(2, 1, 1),
            coveredOverride = 1,
        )
        val result = loader.load(tmp)
        check(result is CanonicalDatasetLoader.LoadResult.Valid) { "expected valid, got $result" }
    }

    @Test
    fun `manifest counts that disagree with the actual files are rejected`(@TempDir tmp: Path) {
        ReferenceDatasetFixture.write(tmp, version = "1.0", countsOverride = Triple(999, 2, 4))
        val result = loader.load(tmp) as CanonicalDatasetLoader.LoadResult.Invalid
        check(result.issues.any { it.contains("counts.settlements") })
    }

    // -------------------------------------------------------------------- helpers

    private fun writeManifestFor(dir: Path, version: String, settlementCount: Int, lineCount: Int, mappingCount: Int) {
        val manifest = """
            {
              "datasetVersion": "$version",
              "verificationStatus": "VERIFIED",
              "coverageStatus": "PARTIAL",
              "reuseStatus": "CLEARED",
              "sources": {},
              "canonicalFiles": {
                "settlements.csv": "${ReferenceDatasetFixture.sha256Hex(dir.resolve("settlements.csv"))}",
                "railway-lines.csv": "${ReferenceDatasetFixture.sha256Hex(dir.resolve("railway-lines.csv"))}",
                "settlement-railway-lines.csv": "${ReferenceDatasetFixture.sha256Hex(dir.resolve("settlement-railway-lines.csv"))}"
              },
              "counts": {
                "settlements": $settlementCount,
                "railwayLines": $lineCount,
                "settlementRailwayLineMappings": $mappingCount
              },
              "coverage": {
                "settlements": "COMPLETE",
                "railwayLines": "COMPLETE",
                "settlementRailwayLines": "COMPLETE",
                "settlementsWithVerifiedRelations": 0
              }
            }
        """.trimIndent()
        Files.writeString(dir.resolve("manifest.json"), manifest)
    }

    private fun rewriteChecksum(dir: Path, fileName: String) {
        val manifestText = Files.readString(dir.resolve("manifest.json"))
        val newHash = ReferenceDatasetFixture.sha256Hex(dir.resolve(fileName))
        val pattern = Regex("\"$fileName\":\\s*\"[0-9a-f]+\"")
        Files.writeString(dir.resolve("manifest.json"), pattern.replace(manifestText, "\"$fileName\": \"$newHash\""))
    }
}
