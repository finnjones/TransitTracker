package app.transittracker.model

/**
 * Where to board at a stop, for every mode: "16" (a train, metro or light rail platform), "Stand A"
 * (a bus stand), "Wharf 3, Side A" (a ferry wharf).
 *
 * TfNSW's GTFS gives `platform_code` only for trains, metro and light rail ("16", "1"). Bus stands
 * are only in the stop's name ("Wynyard Station, Carrington St, Stand A": not one of the ~38,000
 * bus stops has a `platform_code`), and a ferry stop's code is just its side ("A") while the
 * wharf is in the name ("Circular Quay, Wharf 3, Side A"; "Manly, Wharf 1" has no code at all). So
 * the name is read first and the code is the fallback, as the trip planner's `platformName` is.
 */
object Platforms {
    /** One identifier: "16", "3A", "A", "A1". Not a street word ("Bay Rd", "Side St"). */
    private const val ID = """(?:[0-9]{1,3}[A-Za-z]?|[A-Za-z][0-9]{0,2})"""
    private val PART = Regex("""^(?:Platform|Stand|Wharf|Side|Bay)\s+$ID$""", RegexOption.IGNORE_CASE)

    /** "Central Station Platform 16" (Sydney Trains names have no comma before the platform). */
    private val TRAILING = Regex("""\s(Platform\s+$ID)$""", RegexOption.IGNORE_CASE)

    /**
     * The platform named in a stop's [name]: "Central Station, Platform 16" -> "16"; "Wynyard
     * Station, Carrington St, Stand A" -> "Stand A"; "Circular Quay, Wharf 3, Side A" -> "Wharf 3,
     * Side A". Null when the name has none ("Glenayr Ave at Hall St", "Waverton Station, Bay Rd").
     */
    fun fromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val parts = name.split(',').map { it.trim() }.filter { PART.matches(it) }
        val found = parts.joinToString(", ").ifEmpty { null }
            ?: TRAILING.find(name.trim())?.groupValues?.get(1)
            ?: return null
        return found.split(Regex("""\s+""")).joinToString(" ").let {
            if (it.startsWith("Platform ", ignoreCase = true)) it.substring(9) else it
        }
    }

    /** Where to board at [stop]: its name's platform words, else its GTFS platform code; null for neither. */
    fun of(stop: Stop?): String? {
        stop ?: return null
        val code = stop.platformCode?.trim()?.takeIf { it.isNotEmpty() }
        val named = fromName(stop.name)
        // A bare number from the name ("16") says no more than the code; words ("Stand A") say more.
        return if (named != null && !named.first().isDigit()) named else code ?: named
    }
}
