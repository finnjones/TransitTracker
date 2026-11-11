package app.transittracker.server.pipeline

/** Severity of a [ValidationIssue]. Errors block publishing; warnings are only reported. */
enum class Severity { ERROR, WARNING }

/**
 * One problem found in GTFS input.
 *
 * @property code stable machine-readable code, e.g. `unknown_stop`.
 * @property file GTFS file name, if the issue is tied to one.
 * @property line 1-based CSV line, if tied to a row.
 */
data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
) {
    override fun toString(): String =
        "$severity $code${file?.let { " [$it${line?.let { l -> ":$l" } ?: ""}]" } ?: ""}: $message"
}

/**
 * Collected issues. Only the first [maxPerCode] issues of each code are kept verbatim, but
 * [counts] counts every occurrence.
 */
class ValidationReport(val issues: List<ValidationIssue>, val counts: Map<Pair<Severity, String>, Int>) {
    val errorCount: Int get() = counts.filterKeys { it.first == Severity.ERROR }.values.sum()
    val warningCount: Int get() = counts.filterKeys { it.first == Severity.WARNING }.values.sum()
    val hasErrors: Boolean get() = errorCount > 0

    val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }

    /** One line per code with its count, errors first. */
    fun summary(): String = counts.entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .joinToString("; ") { "${it.key.first.name.lowercase()} ${it.key.second} x${it.value}" }
        .ifEmpty { "no issues" }

    /** Accumulates issues while loading and validating. Not thread-safe. */
    class Builder(private val maxPerCode: Int = 50) {
        private val issues = ArrayList<ValidationIssue>()
        private val counts = LinkedHashMap<Pair<Severity, String>, Int>()

        fun add(issue: ValidationIssue) {
            val key = issue.severity to issue.code
            val n = (counts[key] ?: 0) + 1
            counts[key] = n
            if (n <= maxPerCode) issues += issue
        }

        fun error(code: String, message: String, file: String? = null, line: Int? = null) =
            add(ValidationIssue(Severity.ERROR, code, message, file, line))

        fun warning(code: String, message: String, file: String? = null, line: Int? = null) =
            add(ValidationIssue(Severity.WARNING, code, message, file, line))

        fun error(row: GtfsRow, code: String, message: String) = error(code, message, row.fileName, row.line)

        fun warning(row: GtfsRow, code: String, message: String) = warning(code, message, row.fileName, row.line)

        val hasErrors: Boolean get() = counts.keys.any { it.first == Severity.ERROR }

        fun build() = ValidationReport(issues.toList(), counts.toMap())
    }
}

/** GTFS input failed validation or could not be turned into a pack; nothing was published. */
class PackBuildException(message: String, val report: ValidationReport? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)
