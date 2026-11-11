package app.transittracker.ui.format

/**
 * A stop's name for small labels (strip labels, "change at …"): "Strathfield Station Platform 3"
 * -> "Strathfield", "Circular Quay Wharf 4" -> "Circular Quay". Presentation only.
 */
fun shortStopName(name: String): String =
    name.replace(Regex("""\s+(Platform|Wharf|Stand)\s+\S+$"""), "")
        .replace(Regex("""\s+(Station|Light Rail|Wharf)$"""), "")
        .trim()
