package app.transittracker.server.planner

/**
 * A place search read as a street address, so a house number finds the house and not just its
 * street.
 *
 * Checked on the live API (13 Nov 2026, `testdata/planner/live/stop_finder-100-george-st*.json`):
 * `stop_finder` answers "100 George St", "1 Macquarie Street, Sydney NSW 2000" and "12 Albert Road
 * Strathfield" with `singlehouse` locations, but falls back to the whole street when the search
 * has a unit ("5/100 George St") or a suburb TfNSW files the house under differently ("100 George
 * St, Sydney": TfNSW has it in The Rocks). So a unit is dropped before searching, and when a search
 * with a house number finds no house, the number and street alone are searched again.
 */
internal data class AddressQuery(
    /** What is sent to TfNSW: the search without a unit or level in front. */
    val text: String,
    /** The house number searched ("100", "12A"); null when the search does not start with one. */
    val number: String?,
    /** Only the number and street ("100 George St") when the search goes on after the street; else null. */
    val numberAndStreet: String?,
    /** The words after the street (the suburb), without the state or postcode, lower case. */
    val suburbWords: List<String>,
    /** The street's words, lower case, street types shortened ("george", "st"); empty without a house number. */
    val streetWords: List<String> = emptyList(),
) {
    /**
     * Whether a house named [houseName] ("100 George St") is on the street searched: the same
     * words, with "Street" and "St" the same; a search without a street type matches any type.
     */
    fun sameStreet(houseName: String): Boolean {
        if (streetWords.isEmpty()) return false
        val name = streetWordsOf(houseName.substringAfter(' ', ""))
        val typed = streetWords
        if (name == typed) return true
        val typedHasType = typed.size > 1 && typed.last() in TYPE_SHORT.values
        return !typedHasType && name.size == typed.size + 1 && name.last() in TYPE_SHORT.values && name.dropLast(1) == typed
    }


    companion object {
        private val IC = setOf(RegexOption.IGNORE_CASE)

        /** "5/100 …", "Unit 5/100 …", "U5/100 …", "Apt 3B / 12 …". */
        private val UNIT_SLASH = Regex("""^(?:(?:unit|apartment|apt|flat|suite|shop|u)\.?\s*)?[0-9]+[a-z]?\s*/\s*(?=[0-9])""", IC)

        /** "Unit 5, 100 …", "Level 3 1 …", "Suite 12, 1 …". */
        private val UNIT_WORDS = Regex("""^(?:unit|apartment|apt|flat|suite|shop|level|lvl)\.?\s*[0-9]+[a-z]?\s*,?\s+(?=[0-9])""", IC)

        /** A house number or range, then a word: "100 G", "12A A", "12-18 A". */
        private val HOUSE = Regex("""^([0-9]+[a-z]?)(?:\s*-\s*[0-9]+[a-z]?)?\s+(?=\p{L})""", IC)

        private val WORD = Regex("""[^\p{L}\p{N}]+""")
        private val POSTCODE = Regex("""^[0-9]{4}$""")
        private val STATE = setOf("nsw", "australia", "aus")

        /**
         * Street types, long and short, to the short form TfNSW names houses with ("George St",
         * "Anzac Pde", "Loralyn Av"). Never the first word of a street ("St Johns Rd" is a road).
         */
        private val TYPE_SHORT = mapOf(
            "st" to "st", "street" to "st", "rd" to "rd", "road" to "rd", "av" to "av", "ave" to "av", "avenue" to "av",
            "pde" to "pde", "parade" to "pde", "cr" to "cr", "cres" to "cr", "crescent" to "cr", "dr" to "dr", "drive" to "dr",
            "pl" to "pl", "place" to "pl", "ln" to "ln", "lane" to "ln", "hwy" to "hwy", "highway" to "hwy",
            "cct" to "cct", "circuit" to "cct", "cl" to "cl", "close" to "cl", "ct" to "ct", "court" to "ct",
            "tce" to "tce", "terrace" to "tce", "way" to "way", "wy" to "way", "blvd" to "bvd", "bvd" to "bvd", "boulevard" to "bvd",
            "gr" to "gr", "grove" to "gr", "sq" to "sq", "square" to "sq", "esp" to "esp", "esplanade" to "esp",
            "prom" to "prom", "promenade" to "prom", "row" to "row", "gdns" to "gdns", "gardens" to "gdns",
            "pkwy" to "pwy", "pwy" to "pwy", "parkway" to "pwy", "cir" to "cir", "circle" to "cir", "mews" to "mews",
            "loop" to "loop", "trl" to "trl", "trail" to "trl", "glen" to "glen", "rise" to "rise", "walk" to "walk",
        )

        /** A street's words, lower case, its type (not a first word) shortened. */
        private fun streetWordsOf(street: String): List<String> =
            street.lowercase().split(WORD).filter { it.isNotEmpty() }
                .mapIndexed { i, w -> if (i > 0) TYPE_SHORT[w] ?: w else w }

        fun parse(search: String): AddressQuery {
            val text = search.trim()
                .replace(UNIT_SLASH, "")
                .replace(UNIT_WORDS, "")
                .trim()
                .ifEmpty { search.trim() }
            val house = HOUSE.find(text) ?: return AddressQuery(text, null, null, emptyList())
            val number = house.groupValues[1]
            val afterNumber = text.substring(house.range.last + 1)

            // Where the street ends: at the first comma, else after the first street type.
            val comma = afterNumber.indexOf(',')
            val (street, rest) = if (comma >= 0) {
                afterNumber.substring(0, comma).trim() to afterNumber.substring(comma + 1)
            } else {
                val words = afterNumber.trim().split(Regex("\\s+"))
                val end = (1 until words.size).firstOrNull { words[it].lowercase().trimEnd('.') in TYPE_SHORT }
                if (end == null) afterNumber.trim() to "" else words.take(end + 1).joinToString(" ") to words.drop(end + 1).joinToString(" ")
            }
            val suburb = rest.lowercase().split(WORD).filter { it.isNotEmpty() && it !in STATE && !POSTCODE.matches(it) }
            val numberAndStreet = if (rest.isBlank() || street.isEmpty()) null else text.substring(0, house.range.last + 1) + street
            return AddressQuery(text, number, numberAndStreet?.trim(), suburb, streetWordsOf(street))
        }

        /**
         * Whether a house named [house] ("100", "100A", "12-18") is the number [searched]: the same
         * number, or a range that holds it.
         */
        fun sameNumber(house: String, searched: String): Boolean {
            if (house.equals(searched, ignoreCase = true)) return true
            val range = house.split('-').map { it.trim() }
            if (range.size != 2) return false
            val n = searched.toIntOrNull() ?: return false
            val from = range[0].toIntOrNull() ?: return false
            val to = range[1].toIntOrNull() ?: return false
            return n in from..to
        }
    }
}
