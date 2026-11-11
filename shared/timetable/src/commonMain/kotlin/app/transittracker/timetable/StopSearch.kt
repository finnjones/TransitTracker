package app.transittracker.timetable

import app.transittracker.model.Stop

/** Stop-name search rules shared by [TimetablePack] and [CompositeTimetable]. */
object StopSearch {
    private val whitespace = Regex("\\s+")

    /** Lower-cased words of a query. */
    fun words(query: String): List<String> =
        query.trim().lowercase().split(whitespace).filter { it.isNotEmpty() }

    /** True if every word occurs in the stop name (case-insensitive). */
    fun matches(stop: Stop, words: List<String>): Boolean {
        val name = stop.name.lowercase()
        return words.all { name.contains(it) }
    }

    /** Escapes `%`, `_` and `\` for a SQL `LIKE ... ESCAPE '\'` pattern. */
    fun escapeLike(text: String): String = buildString {
        for (c in text) {
            if (c == '%' || c == '_' || c == '\\') append('\\')
            append(c)
        }
    }

    /**
     * Ranking: stations and standalone stops (no parent) before platforms; then names starting
     * with the query, then names with a word starting with it, then others; then shorter names,
     * then alphabetical, then id.
     */
    fun comparator(query: String): Comparator<Stop> {
        val q = query.trim().lowercase()
        return compareBy<Stop>(
            { if (it.parentId == null) 0 else 1 },
            {
                val name = it.name.lowercase()
                when {
                    name.startsWith(q) -> 0
                    name.split(' ', '-', '(').any { w -> w.startsWith(q) } -> 1
                    else -> 2
                }
            },
            { it.name.length },
            { it.name },
            { it.id },
        )
    }
}
