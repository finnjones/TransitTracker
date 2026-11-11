package app.transittracker.ui.feature.settings

import app.transittracker.model.DataStatus
import app.transittracker.model.PackStatus
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.theme.ColorRole
import kotlinx.datetime.TimeZone

/** The five groups of the Settings page (plan 5.11), in order. */
enum class SettingsGroup(val title: String) {
    PLACES("Places"),
    DATA("Data"),
    DISPLAY("Display"),
    NOTIFICATIONS("Notifications"),
    ABOUT("About"),
}

// ---------------------------------------------------------------- data

/** "12 seconds ago", "3 minutes ago", "2 hours ago", "yesterday", "5 days ago". */
fun agoWords(ageMs: Long): String {
    val s = (ageMs / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> "just now"
        s < 60 -> "$s seconds ago"
        s < 3600 -> (s / 60).let { if (it == 1L) "1 minute ago" else "$it minutes ago" }
        s < 86_400 -> (s / 3600).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
        s < 2 * 86_400 -> "yesterday"
        else -> "${s / 86_400} days ago"
    }
}

/** The live-data line of the Data group: a lamp, a colour role, words, and when it last updated. */
data class LiveWords(
    val lamp: Lamp,
    val role: ColorRole,
    val headline: String,
    val lastUpdate: String,
)

fun liveWords(status: DataStatus?, nowMs: Long): LiveWords {
    if (status == null) return LiveWords(Lamp.RING, ColorRole.INFO, "Checking", "")
    val last = status.lastLiveUpdateMs?.let { "Last live update ${agoWords(nowMs - it)}" } ?: "No live update yet"
    return when {
        !status.serverReachable -> LiveWords(
            Lamp.RING, ColorRole.INFO, "Offline. Showing the timetable, not live positions.", last,
        )
        status.liveStale -> LiveWords(Lamp.TRIANGLE, ColorRole.CAUTION, "Live data is out of date.", last)
        else -> LiveWords(Lamp.CIRCLE, ColorRole.GO, "Live data is up to date.", last)
    }
}

enum class PackState { UP_TO_DATE, UPDATE_AVAILABLE, DOWNLOADING, OUT_OF_DATE, MISSING }

/** One timetable pack as the Data group lists it. */
data class PackRow(
    val title: String,
    /** "Version 20261110", or "Not downloaded yet". */
    val version: String,
    /** "Valid to 31 Dec 2026". */
    val validTo: String,
    val state: PackState,
    /** "Up to date", "Update available", "Downloading", "Out of date", "Not downloaded". */
    val stateWords: String,
) {
    val spoken: String get() = "$title. $stateWords. $version. $validTo."
}

/** "syd-rail" -> "Syd rail": the server's pack name made readable. */
fun packTitle(name: String): String {
    val words = name.replace('-', ' ').replace('_', ' ').trim()
    return if (words.isEmpty()) "Timetable" else words.replaceFirstChar { it.uppercase() }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Valid to 31 Dec 2026"; "No end date" when the server gave none. */
fun validToWords(validTo: ServiceDate?): String {
    if (validTo.isNullOrEmpty()) return "No end date"
    val date = runCatching { ServiceTime.parseDate(validTo) }.getOrNull() ?: return "No end date"
    return "Valid to ${date.day} ${MONTHS[date.month.ordinal]} ${date.year}"
}

/** [today] is a service date ("20261111"): a pack that ended before it is out of date. */
fun packRow(pack: PackStatus, today: ServiceDate): PackRow {
    val state = when {
        pack.downloading -> PackState.DOWNLOADING
        pack.version == null -> PackState.MISSING
        pack.validTo.let { !it.isNullOrEmpty() && it < today } -> PackState.OUT_OF_DATE
        pack.updateAvailable -> PackState.UPDATE_AVAILABLE
        else -> PackState.UP_TO_DATE
    }
    val words = when (state) {
        PackState.DOWNLOADING -> "Downloading"
        PackState.MISSING -> "Not downloaded"
        PackState.OUT_OF_DATE -> "Out of date"
        PackState.UPDATE_AVAILABLE -> "Update available"
        PackState.UP_TO_DATE -> "Up to date"
    }
    return PackRow(
        packTitle(pack.name),
        pack.version?.let { "Version $it" } ?: "Not downloaded yet",
        validToWords(pack.validTo),
        state,
        words,
    )
}

fun today(nowMs: Long, zone: TimeZone): ServiceDate = ServiceTime.localDate(nowMs, zone)

// ---------------------------------------------------------------- notifications

/** The permission as a short state and one plain sentence. */
data class PermissionWords(val state: String, val sentence: String, val lamp: Lamp, val role: ColorRole)

fun permissionWords(permission: PermissionState): PermissionWords = when (permission) {
    PermissionState.GRANTED -> PermissionWords(
        "ON", "Notifications are on. Late and cancelled trains can reach you.", Lamp.CIRCLE, ColorRole.GO,
    )
    PermissionState.NOT_ASKED -> PermissionWords(
        "NOT SET", "TransitTracker has not asked yet. Allow notifications to hear about late trains.", Lamp.RING, ColorRole.INFO,
    )
    PermissionState.DENIED -> PermissionWords(
        "OFF", "Notifications are off, so a late train will not reach you. Turn them on in your phone's settings.",
        Lamp.TRIANGLE, ColorRole.CAUTION,
    )
}

const val BATTERY_TIP =
    "If alerts arrive late, allow TransitTracker to run in the background in your phone's settings."
const val BATTERY_SAVER_ON = "Battery saver is on, so your phone may hold notifications back. "

/** What SEND TEST did; [words] is the result line under the button. */
enum class TestOutcome { SENT, NO_WATCH, REFUSED, UNREACHABLE }

fun testWords(outcome: TestOutcome): String = when (outcome) {
    TestOutcome.SENT -> "Test sent. It should arrive in a moment."
    TestOutcome.NO_WATCH ->
        "There is no watch to send it through. Add one on the Notify tab, then try again."
    TestOutcome.REFUSED -> "The server could not send it. Try again in a moment."
    TestOutcome.UNREACHABLE -> "Can't reach the server."
}

// ---------------------------------------------------------------- about

const val DEVICE_ID_NOTE =
    "This is how the server knows your phone. If you lose it, give this ID to the person who invited you so they can remove it."

/** One font in the licences list. */
data class FontLicence(
    val name: String,
    val use: String,
    /** Path under the compose resources, "files/licenses/OFL-Doto.txt". */
    val file: String,
)

val FONT_LICENCES: List<FontLicence> = listOf(
    FontLicence("Pixelify Sans", "Titles, names and text", "files/licenses/OFL-PixelifySans.txt"),
    FontLicence("Silkscreen", "Labels and buttons", "files/licenses/OFL-Silkscreen.txt"),
    FontLicence("Doto", "Clocks and countdowns", "files/licenses/OFL-Doto.txt"),
    FontLicence("Jersey 15", "Numbers", "files/licenses/OFL-Jersey15.txt"),
)

const val LICENCE_NAME = "SIL Open Font License 1.1"

/** The copyright notice of a licence file: its first line starting with "Copyright"; blank if none. */
fun copyrightLine(licenceText: String): String =
    licenceText.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Copyright", ignoreCase = true) }.orEmpty()

/** "Modified for legibility by this app." when the file opens with a MODIFIED VERSION notice. */
fun modifiedNote(licenceText: String): String? =
    if (licenceText.trimStart().startsWith("MODIFIED VERSION")) "Modified for legibility by this app." else null
