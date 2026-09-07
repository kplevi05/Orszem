package hu.orszembejelento.backend.reference.support

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Writes a small, fully synthetic canonical reference dataset to a directory for tests.
 *
 * Deliberately never touches `reference-data/local-research/` — the real VPE-derived dataset stays
 * `reuseStatus: PENDING` and is never imported, including in tests (ADR 0006). Everything
 * here is invented data with no bearing on any real settlement or railway line.
 *
 * Fully deterministic: no timestamps, no random content. Writing the same arguments twice
 * produces byte-identical files, which is exactly what the idempotent-import tests rely on.
 */
object ReferenceDatasetFixture {

    val defaultSettlements = listOf(
        Triple("00001", "Alfa", "Megye A"),
        Triple("00002", "Beta", "Megye B"),
        Triple("00003", "Gamma", "Megye C"),
    )
    val defaultLines = listOf("1" to "Alfa - Beta", "2" to "Beta - Gamma")
    val defaultRelations = listOf("00001" to "1", "00002" to "1", "00002" to "2", "00003" to "2")

    fun write(
        dir: Path,
        version: String,
        verification: String = "VERIFIED",
        coverage: String = "PARTIAL",
        reuse: String = "CLEARED",
        settlements: List<Triple<String, String, String?>> = defaultSettlements,
        lines: List<Pair<String, String>> = defaultLines,
        relations: List<Pair<String, String>> = defaultRelations,
        coveredOverride: Int? = null,
        countsOverride: Triple<Int, Int, Int>? = null,
    ): Path {
        Files.createDirectories(dir)

        val settlementsCsv = buildString {
            append("ksh_code,name,county_name\n")
            settlements.forEach { (ksh, name, county) -> append("$ksh,$name,${county.orEmpty()}\n") }
        }
        val linesCsv = buildString {
            append("line_code,display_name\n")
            lines.forEach { (code, name) -> append("$code,$name\n") }
        }
        val relationsCsv = buildString {
            append("ksh_code,line_code\n")
            relations.forEach { (ksh, line) -> append("$ksh,$line\n") }
        }

        Files.writeString(dir.resolve("settlements.csv"), settlementsCsv)
        Files.writeString(dir.resolve("railway-lines.csv"), linesCsv)
        Files.writeString(dir.resolve("settlement-railway-lines.csv"), relationsCsv)

        val covered = coveredOverride ?: relations.map { it.first }.toSet().size
        val (settlementCount, lineCount, mappingCount) = countsOverride
            ?: Triple(settlements.size, lines.size, relations.size)

        val manifest = """
            {
              "datasetVersion": "$version",
              "verificationStatus": "$verification",
              "coverageStatus": "$coverage",
              "reuseStatus": "$reuse",
              "sources": {
                "TEST": { "source": "synthetic test fixture, not a real source", "used": "test only" }
              },
              "canonicalFiles": {
                "settlements.csv": "${sha256Hex(dir.resolve("settlements.csv"))}",
                "railway-lines.csv": "${sha256Hex(dir.resolve("railway-lines.csv"))}",
                "settlement-railway-lines.csv": "${sha256Hex(dir.resolve("settlement-railway-lines.csv"))}"
              },
              "counts": {
                "settlements": $settlementCount,
                "railwayLines": $lineCount,
                "settlementRailwayLineMappings": $mappingCount
              },
              "coverage": {
                "settlementsWithVerifiedRelations": $covered
              }
            }
        """.trimIndent()

        Files.writeString(dir.resolve("manifest.json"), manifest)
        return dir
    }

    fun sha256Hex(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}
