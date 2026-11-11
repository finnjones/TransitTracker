package app.transittracker.ui.feature.onboarding

import app.transittracker.model.DataStatus

/** The server's invite alphabet: no I, L, O, U, 0 or 1 (plan 5.1). */
const val INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

/** Characters in an invite code, not counting the dash. */
const val INVITE_LENGTH = 8

private const val HALF = INVITE_LENGTH / 2

/** Letters and digits only, upper case, at most eight: what the user has typed so far. */
fun cleanInviteInput(raw: String): String = lettersAndDigits(raw).take(INVITE_LENGTH)

private fun lettersAndDigits(raw: String): String = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

/**
 * What the code field shows for [raw]: lower case becomes upper case, spaces and dashes are
 * dropped, and the dash appears after the fourth character as soon as there is a fifth.
 */
fun formatInviteInput(raw: String): String {
    val c = cleanInviteInput(raw)
    return if (c.length > HALF) c.take(HALF) + "-" + c.drop(HALF) else c
}

/** The code to send to the server (`XXXX-XXXX`), or null while [raw] is not eight valid characters. */
fun normaliseInviteCode(raw: String): String? {
    val c = lettersAndDigits(raw) // not cut to length: a ninth character means this is not a code
    if (c.length != INVITE_LENGTH || c.any { it !in INVITE_ALPHABET }) return null
    return c.take(HALF) + "-" + c.drop(HALF)
}

/** The characters in [raw] that the server never uses, once each, in the order typed. */
fun impossibleCharacters(raw: String): List<Char> =
    cleanInviteInput(raw).filter { it !in INVITE_ALPHABET }.toList().distinct()

/** "Invite codes never contain O or 0." for the characters in [bad]; null when there are none. */
fun impossibleCharactersHint(bad: List<Char>): String? {
    if (bad.isEmpty()) return null
    val items = bad.map { it.toString() }
    val list = if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " or " + items.last()
    return "Invite codes never contain $list."
}

const val INVALID_CODE_WORDS = "That code isn't valid or has been used"
const val UNREACHABLE_WORDS = "Can't reach the server"

/** The four steps, in order (plan 5.1). */
enum class OnboardingStep {
    INVITE, DOWNLOAD, NOTIFICATIONS, JOURNEY;

    /** "STEP 2 OF 4". */
    val words: String get() = "STEP ${ordinal + 1} OF ${entries.size}"
}

/** The step after [from]; the notifications step is skipped when they are already allowed. */
fun nextStep(from: OnboardingStep, notificationsAllowed: Boolean): OnboardingStep? = when (from) {
    OnboardingStep.INVITE -> OnboardingStep.DOWNLOAD
    OnboardingStep.DOWNLOAD -> if (notificationsAllowed) OnboardingStep.JOURNEY else OnboardingStep.NOTIFICATIONS
    OnboardingStep.NOTIFICATIONS -> OnboardingStep.JOURNEY
    OnboardingStep.JOURNEY -> null
}

enum class JoinStatus { IDLE, WORKING, INVALID, UNREACHABLE }

// ---------------------------------------------------------------- the download

enum class DownloadPhase {
    /** Asking the server which timetables there are. */
    ASKING,
    DOWNLOADING,
    READY,

    /** The phone cannot reach the server. */
    OFFLINE,

    /** Reachable, but nothing has moved for a while. */
    STALLED,

    /** The server answered, and the core says why no timetable came ([DownloadUi.detail]). */
    FAILED,
}

/**
 * The download step as drawn. The contract reports packs, not bytes, so progress is by pack:
 * a pack with a version is done, one being downloaded counts half, one waiting counts nothing.
 */
data class DownloadUi(
    val phase: DownloadPhase = DownloadPhase.ASKING,
    val fraction: Float = 0f,
    val packsDone: Int = 0,
    val packsTotal: Int = 0,
    /** The headline under the scene, "Downloading timetable 2 of 3". */
    val words: String = "Asking the server which timetables you need",
    /** Why it failed, from the core ([DataStatus.timetableProblem]), for [DownloadPhase.FAILED]. */
    val detail: String? = null,
) {
    val percent: Int get() = (fraction * 100f).toInt().coerceIn(0, 100)
    val percentWords: String get() = "$percent%"

    /** True when RETRY makes sense. */
    val failed: Boolean get() =
        phase == DownloadPhase.OFFLINE || phase == DownloadPhase.STALLED || phase == DownloadPhase.FAILED

    /** What the screen reader says for the whole scene. */
    val spoken: String get() = if (phase == DownloadPhase.READY) words else "$words, $percent percent"
}

/** Progress and words for the download step from the core's [status] (null before it has spoken). */
fun downloadProgress(status: DataStatus?): DownloadUi {
    if (status == null) return DownloadUi()
    val packs = status.packs
    val total = packs.size
    val done = packs.count { it.version != null && !it.downloading }
    val busy = packs.count { it.downloading }
    val fraction = if (total == 0) 0f else (done + 0.5f * busy) / total
    return when {
        total > 0 && done == total -> DownloadUi(DownloadPhase.READY, 1f, done, total, "Timetable ready")
        !status.serverReachable -> DownloadUi(
            DownloadPhase.OFFLINE, fraction, done, total,
            "Can't reach the server, so the timetable has not finished",
        )
        busy == 0 && status.timetableProblem != null -> DownloadUi(
            DownloadPhase.FAILED, fraction, done, total, "The timetable did not arrive", status.timetableProblem,
        )
        total == 0 -> DownloadUi()
        else -> DownloadUi(
            DownloadPhase.DOWNLOADING, fraction, done, total,
            if (total == 1) "Downloading the timetable" else "Downloading timetable ${minOf(done + 1, total)} of $total",
        )
    }
}

/** This download marked as stopped: reachable, but nothing has moved for a while. */
fun DownloadUi.stalled(): DownloadUi =
    if (phase == DownloadPhase.DOWNLOADING || phase == DownloadPhase.ASKING) {
        copy(phase = DownloadPhase.STALLED, words = "The download has stopped")
    } else this
