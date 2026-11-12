package app.transittracker.ui.format

/**
 * A stop's name for small labels (strip labels, "change at …"): "Strathfield Station Platform 3"
 * -> "Strathfield", "Circular Quay Wharf 4" -> "Circular Quay". Presentation only.
 */
fun shortStopName(name: String): String =
    name.replace(Regex(""",?\s+(Platform|Wharf|Stand)\s+\S+$"""), "")
        .replace(Regex("""\s+(Station|Light Rail|Wharf)$"""), "")
        .trim()

/**
 * A stop's name for a map label, shorter still: a bus stop is named "place, street"
 * ("Bondi Beach, Campbell Pde", "Taylor Square, Oxford St"), and on the map the place is enough
 * (the street is where the line is). Lists keep [shortStopName].
 */
fun mapStopName(name: String): String = shortStopName(name).substringBefore(", ").trim()
