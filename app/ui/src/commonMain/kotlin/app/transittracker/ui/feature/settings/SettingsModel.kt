package app.transittracker.ui.feature.settings

import app.transittracker.model.DataStatus
import app.transittracker.model.PackStatus
import app.transittracker.model.ServiceDate
import app.transittracker.model.ServiceTime
import app.transittracker.ui.format.Lamp
import app.transittracker.ui.platform.AppUpdate
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.platform.UpdateProblem
import app.transittracker.ui.platform.UpdateState
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

/** What the update button does. */
enum class UpdateAction { CHECK, INSTALL }

/**
 * The app-update rows of the About group: a lamp and colour (none before the first check), a
 * headline, a sentence, and the button ([button] null for none; [busy] draws it disabled).
 */
data class UpdateWords(
    val lamp: Lamp?,
    val role: ColorRole,
    val headline: String,
    val detail: String?,
    val button: String?,
    val action: UpdateAction = UpdateAction.CHECK,
    val busy: Boolean = false,
    /** The release notes of an update on offer. */
    val notes: String? = null,
)

/** "31.4 MB": one decimal, from bytes; blank when the size is unknown. */
fun sizeWords(bytes: Long): String {
    if (bytes <= 0) return ""
    val tenths = (bytes + 50_000) / 100_000
    return "${tenths / 10}.${tenths % 10} MB"
}

private fun versionWords(update: AppUpdate) = "Version ${update.versionName}"

/** [state] null: this build cannot update itself. */
fun updateWords(state: UpdateState?): UpdateWords = when (state) {
    null -> UpdateWords(
        null, ColorRole.SECONDARY, "Updates are not set up for this build.",
        "Install new versions by hand.", null,
    )
    UpdateState.Idle -> UpdateWords(
        null, ColorRole.SECONDARY, "App updates", "Check for a newer version of TransitTracker.", "CHECK FOR UPDATES",
    )
    UpdateState.Checking -> UpdateWords(
        Lamp.RING, ColorRole.INFO, "Checking for updates", null, "CHECKING...", busy = true,
    )
    UpdateState.UpToDate -> UpdateWords(
        Lamp.CIRCLE, ColorRole.GO, "You have the latest version.", null, "CHECK AGAIN",
    )
    is UpdateState.Available -> UpdateWords(
        Lamp.RING, ColorRole.INFO, "${versionWords(state.update)} is ready to install.",
        sizeWords(state.update.sizeBytes).let { if (it.isEmpty()) null else "$it download." },
        "INSTALL UPDATE", UpdateAction.INSTALL, notes = state.update.notes.ifBlank { null },
    )
    is UpdateState.Downloading -> {
        val percent = state.fraction?.let { "${(it.coerceIn(0f, 1f) * 100).toInt()}%" }
        UpdateWords(
            Lamp.RING, ColorRole.INFO, "Downloading ${versionWords(state.update).lowercase()}",
            "Keep TransitTracker open until it finishes.",
            if (percent == null) "DOWNLOADING..." else "DOWNLOADING $percent", UpdateAction.INSTALL, busy = true,
        )
    }
    is UpdateState.Installing -> UpdateWords(
        Lamp.RING, ColorRole.INFO, "Installing ${versionWords(state.update).lowercase()}",
        "If your phone asks, tap Update. TransitTracker closes while it updates; open it again afterwards.",
        "INSTALLING...", UpdateAction.INSTALL, busy = true,
    )
    is UpdateState.NeedsPermission -> UpdateWords(
        Lamp.TRIANGLE, ColorRole.CAUTION, "Allow TransitTracker to install updates",
        "Your phone asks first. Turn on \"Allow from this source\", then come back here.",
        "OPEN PHONE SETTINGS", UpdateAction.INSTALL,
    )
    is UpdateState.Failed -> problemWords(state)
}

private fun problemWords(state: UpdateState.Failed): UpdateWords {
    val retry = if (state.update != null) UpdateAction.INSTALL else UpdateAction.CHECK
    return when (state.problem) {
        UpdateProblem.CHECK_FAILED -> UpdateWords(
            Lamp.TRIANGLE, ColorRole.CAUTION, "Couldn't check for updates.", "Check your connection and try again.",
            "TRY AGAIN", UpdateAction.CHECK,
        )
        UpdateProblem.DOWNLOAD_FAILED -> UpdateWords(
            Lamp.TRIANGLE, ColorRole.CAUTION, "The download didn't finish.", "Check your connection and try again.",
            "TRY AGAIN", retry,
        )
        UpdateProblem.BAD_DOWNLOAD -> UpdateWords(
            Lamp.TRIANGLE, ColorRole.CAUTION, "The download was damaged.", "Nothing was installed. Try again.",
            "TRY AGAIN", retry,
        )
        UpdateProblem.DIFFERENT_SIGNATURE -> UpdateWords(
            Lamp.CROSS, ColorRole.STOP, "Your phone refused this update.",
            "This copy of TransitTracker was signed with a different key, so updates can't install over it. " +
                "Uninstall it once and install the latest release by hand (you will need a new invite code). " +
                "Updates install from the app after that.",
            null,
        )
        UpdateProblem.CANCELLED -> UpdateWords(
            Lamp.RING, ColorRole.INFO, "Update not installed.", "Install it whenever you're ready.",
            "INSTALL UPDATE", retry,
        )
        UpdateProblem.INSTALL_FAILED -> UpdateWords(
            Lamp.TRIANGLE, ColorRole.CAUTION, "Your phone couldn't install the update.", "Try again in a moment.",
            "TRY AGAIN", retry,
        )
    }
}

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

/** The base map's data (feature/map/Basemap.kt): its credit, licence and bundled notice. */
const val MAP_DATA_CREDIT = "Map data © OpenStreetMap contributors."
const val MAP_DATA_LICENCE = "Open Database License 1.0"
const val MAP_DATA_FILE = "files/licenses/ODbL-OpenStreetMap.txt"

/** The copyright notice of a licence file: its first line starting with "Copyright"; blank if none. */
fun copyrightLine(licenceText: String): String =
    licenceText.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Copyright", ignoreCase = true) }.orEmpty()

/** "Modified for legibility by this app." when the file opens with a MODIFIED VERSION notice. */
fun modifiedNote(licenceText: String): String? =
    if (licenceText.trimStart().startsWith("MODIFIED VERSION")) "Modified for legibility by this app." else null
