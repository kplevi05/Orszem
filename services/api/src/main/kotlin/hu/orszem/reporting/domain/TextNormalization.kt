package hu.orszem.reporting.domain

/**
 * BUSINESS_RULES.md §2: for `trainIdentifier` and `settlement`, trim and collapse
 * runs of whitespace to a single space. No semantic correction is performed.
 */
object TextNormalization {
    private val whitespaceRun = Regex("\\s+")

    fun normalize(raw: String): String = raw.trim().replace(whitespaceRun, " ")
}
