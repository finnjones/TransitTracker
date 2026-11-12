package app.transittracker.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.transittracker.ui.app.LocalAppDeps
import app.transittracker.ui.components.ButtonKind
import app.transittracker.ui.components.DashedDivider
import app.transittracker.ui.components.Label
import app.transittracker.ui.components.LabelSize
import app.transittracker.ui.components.PixelButton
import app.transittracker.ui.components.PixelIcon
import app.transittracker.ui.components.PixelIcons
import app.transittracker.ui.components.PixelPanel
import app.transittracker.ui.components.PixelText
import app.transittracker.ui.components.SectionLabel
import app.transittracker.ui.components.StatusLamp
import app.transittracker.ui.components.SwitchRow
import app.transittracker.ui.components.ToggleChip
import app.transittracker.ui.motion.flapRow
import app.transittracker.ui.nav.LocalNavigator
import app.transittracker.ui.nav.OnBack
import app.transittracker.ui.nav.Route
import app.transittracker.ui.platform.PermissionState
import app.transittracker.ui.settings.CardDensity
import app.transittracker.ui.settings.ClockFormat
import app.transittracker.ui.settings.ThemePreference
import app.transittracker.ui.state.rememberStateHolder
import app.transittracker.ui.theme.TtTheme

/** What Settings can ask for; the screen maps these to the holder and to navigation. */
class SettingsActions(
    val openPlaces: () -> Unit = {},
    val openRoutes: () -> Unit = {},
    val setTheme: (ThemePreference) -> Unit = {},
    val setClockFormat: (ClockFormat) -> Unit = {},
    val setCardDensity: (CardDensity) -> Unit = {},
    val setReduceMotion: (Boolean) -> Unit = {},
    val setSounds: (Boolean) -> Unit = {},
    val setHaptics: (Boolean) -> Unit = {},
    val setFollowJourney: (Boolean) -> Unit = {},
    val allowLocation: () -> Unit = {},
    val allow: () -> Unit = {},
    val openPhoneSettings: () -> Unit = {},
    val sendTest: () -> Unit = {},
    val checkNow: () -> Unit = {},
    val updateApp: (UpdateAction) -> Unit = {},
    val openLicences: () -> Unit = {},
    val closeLicences: () -> Unit = {},
    val openDebugGallery: () -> Unit = {},
    val signOut: () -> Unit = {},
    val signIn: () -> Unit = {},
)

/** The Settings tab (plan 5.11): five groups on one scrolling page, and the licences page. */
@Composable
fun SettingsScreen() {
    val deps = LocalAppDeps.current
    val nav = LocalNavigator.current
    val zone = TtTheme.time.timeZone
    val holder = rememberStateHolder { scope ->
        SettingsStateHolder(deps.core, deps.settings, deps.places, deps.platform, deps.clock, scope, zone)
    }
    val state by holder.state.collectAsState()
    var licences by rememberSaveable { mutableStateOf(false) }
    val actions = remember(nav, holder) {
        SettingsActions(
            openPlaces = { nav.push(Route.SavedPlaces) },
            openRoutes = { nav.push(Route.ManageRoutes) },
            setTheme = holder::setTheme,
            setClockFormat = holder::setClockFormat,
            setCardDensity = holder::setCardDensity,
            setReduceMotion = holder::setReduceMotion,
            setSounds = holder::setSounds,
            setHaptics = holder::setHaptics,
            setFollowJourney = holder::setFollowJourney,
            allowLocation = holder::allowLocation,
            allow = holder::allowNotifications,
            openPhoneSettings = holder::openPhoneSettings,
            sendTest = holder::sendTest,
            checkNow = holder::checkNow,
            updateApp = holder::updateApp,
            openLicences = { licences = true },
            closeLicences = { licences = false },
            openDebugGallery = { nav.push(Route.DebugGallery) },
            signOut = holder::signOut,
            signIn = holder::signIn,
        )
    }
    // Back closes the licences page before it leaves the tab.
    OnBack(enabled = licences) { licences = false }
    if (licences) LicencesPage(state.data.attribution, actions.closeLicences)
    else SettingsContent(state, actions)
}

/** The page for [state]; stateless. [listState] lets a preview scroll to a group. */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    Column(modifier.fillMaxSize().background(TtTheme.colors.screen)) {
        PixelText(
            "Settings",
            Modifier.flapRow(0).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp).semantics { heading() },
            TtTheme.type.title22,
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "places") { Group(SettingsGroup.PLACES, 1) { PlacesGroup(state, actions) } }
            item(key = "data") { Group(SettingsGroup.DATA, 2) { DataGroup(state, actions) } }
            item(key = "display") { Group(SettingsGroup.DISPLAY, 3) { DisplayGroup(state, actions) } }
            item(key = "notifications") { Group(SettingsGroup.NOTIFICATIONS, 4) { NotificationsGroup(state, actions) } }
            item(key = "about") { Group(SettingsGroup.ABOUT, 5) { AboutGroup(state, actions) } }
        }
    }
}

/** The list index of [group]'s item, for [LazyListState.scrollToItem]. */
fun groupIndex(group: SettingsGroup): Int = group.ordinal

@Composable
private fun Group(group: SettingsGroup, row: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().flapRow(row), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(group.title)
        PixelPanel(Modifier.fillMaxWidth(), verticalSpacing = 4.dp) { content() }
    }
}

// ---------------------------------------------------------------- shared rows

/** A row that opens something: title, an optional value in secondary text, a chevron. */
@Composable
private fun NavRow(title: String, value: String?, onClick: () -> Unit, divider: Boolean = true, hint: String? = null) {
    val c = TtTheme.colors
    val source = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        if (divider) DashedDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = TtTheme.spacing.touch)
                .clickable(source, null, role = Role.Button, onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 4.dp)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PixelText(title)
                if (value != null) PixelText(value, style = TtTheme.type.reading15, color = c.textSecondary)
                if (hint != null) Label(hint, color = c.textSecondary, size = LabelSize.L10)
            }
            PixelIcon(PixelIcons.ChevronRight, tint = c.textSecondary)
        }
    }
}

/** A name over a row of [ToggleChip]s, one of which is selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, divider: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (divider) DashedDivider()
        Label(label, color = TtTheme.colors.textSecondary, bold = true, size = LabelSize.L10, modifier = Modifier.padding(top = 4.dp))
        FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((text, value) in options) {
                ToggleChip(
                    text, value == selected,
                    { if (it) onSelect(value) },
                    Modifier.semantics { contentDescription = "$label: $text" },
                )
            }
        }
    }
}

@Composable
private fun StateLine(lamp: app.transittracker.ui.format.Lamp, role: app.transittracker.ui.theme.ColorRole, headline: String, detail: String? = null) {
    val c = TtTheme.colors
    val color = c.of(role)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.padding(top = 4.dp)) { StatusLamp(lamp, color, size = 14.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PixelText(headline, style = TtTheme.type.row15Strong)
            if (!detail.isNullOrEmpty()) PixelText(detail, style = TtTheme.type.reading15, color = c.textSecondary)
        }
    }
}

// ---------------------------------------------------------------- places

@Composable
private fun PlacesGroup(state: SettingsUiState, actions: SettingsActions) {
    NavRow("Saved places", countWords(state.savedPlaces, "place"), actions.openPlaces, divider = false)
    NavRow("Manage routes", countWords(state.savedRoutes, "route"), actions.openRoutes)
    SwitchRow("Follow my journey", state.display.followJourney, actions.setFollowJourney)
    PixelText(
        followWords(state.display.followJourney, state.location),
        Modifier.padding(bottom = 4.dp), TtTheme.type.reading15, TtTheme.colors.textSecondary,
    )
    if (state.display.followJourney && state.location == PermissionState.NOT_ASKED) {
        PixelButton("ALLOW LOCATION", actions.allowLocation, Modifier.fillMaxWidth().padding(bottom = 8.dp), style = TtTheme.type.label11Bold)
    }
}

/** The sentence under Follow my journey. */
fun followWords(on: Boolean, location: PermissionState): String = when {
    !on -> "Off. Home always shows the next departures of each route."
    location == PermissionState.GRANTED ->
        "While the app is open, your location shows which train or bus you are on, so Home keeps your connection in view."
    location == PermissionState.DENIED ->
        "Location is off for TransitTracker, so it cannot tell which service you are on. Turn it on in your phone's settings."
    else -> "Allow location so TransitTracker can tell which train or bus you are on."
}

fun countWords(n: Int, noun: String): String = when (n) {
    0 -> "No ${noun}s yet"
    1 -> "1 $noun"
    else -> "$n ${noun}s"
}

// ---------------------------------------------------------------- data

@Composable
private fun DataGroup(state: SettingsUiState, actions: SettingsActions) {
    val c = TtTheme.colors
    val data = state.data
    StateLine(data.live.lamp, data.live.role, data.live.headline, data.live.lastUpdate)
    data.message?.let { m ->
        PixelText(m, Modifier.padding(start = 24.dp), TtTheme.type.reading15, c.textSecondary)
    }
    if (data.packs.isEmpty()) {
        DashedDivider()
        PixelText("No timetable on this phone yet.", Modifier.padding(vertical = 8.dp), TtTheme.type.reading15, c.textReading)
    }
    for (pack in data.packs) {
        DashedDivider()
        val role = when (pack.state) {
            PackState.UP_TO_DATE -> c.go
            PackState.UPDATE_AVAILABLE, PackState.DOWNLOADING -> c.info
            PackState.OUT_OF_DATE, PackState.MISSING -> c.caution
        }
        Column(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).clearAndSetSemantics { contentDescription = pack.spoken },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                PixelText(pack.title, Modifier.weight(1f), TtTheme.type.name17)
                Label(pack.stateWords.uppercase(), color = role, bold = true, size = LabelSize.L10)
            }
            PixelText("${pack.version}. ${pack.validTo}.", style = TtTheme.type.reading15, color = c.textSecondary)
        }
    }
    if (data.problem != null || data.packs.any { it.state == PackState.MISSING || it.state == PackState.OUT_OF_DATE }) {
        DashedDivider()
        data.problem?.let { PixelText(it, Modifier.padding(top = 8.dp, bottom = 8.dp), TtTheme.type.reading15, c.caution) }
        PixelButton(
            if (data.checking) "CHECKING..." else "CHECK NOW", actions.checkNow,
            Modifier.fillMaxWidth(), enabled = !data.checking, kind = ButtonKind.SECONDARY, style = TtTheme.type.label11Bold,
        )
    }
    if (data.attribution.isNotBlank()) {
        DashedDivider()
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("ATTRIBUTION", color = c.textSecondary, bold = true, size = LabelSize.L10)
            PixelText(data.attribution, style = TtTheme.type.reading15, color = c.textReading)
        }
    }
}

// ---------------------------------------------------------------- display

@Composable
private fun DisplayGroup(state: SettingsUiState, actions: SettingsActions) {
    val d = state.display
    ChoiceRow(
        "THEME",
        listOf("NIGHT" to ThemePreference.NIGHT, "DAY" to ThemePreference.DAY, "FOLLOW PHONE" to ThemePreference.SYSTEM),
        d.theme, actions.setTheme, divider = false,
    )
    ChoiceRow("TIME", listOf("24-HOUR" to ClockFormat.H24, "12-HOUR" to ClockFormat.H12), d.clockFormat, actions.setClockFormat)
    ChoiceRow(
        "CARD DENSITY", listOf("COMFORTABLE" to CardDensity.COMFORTABLE, "COMPACT" to CardDensity.COMPACT),
        d.cardDensity, actions.setCardDensity,
    )
    SwitchRow("Reduce motion", d.reduceMotion, actions.setReduceMotion)
    if (state.systemReduceMotion || state.batterySaver) {
        PixelText(
            if (state.systemReduceMotion) "Your phone's reduce-motion setting is on, so motion is already off."
            else "Battery saver is on, so motion is already off.",
            Modifier.padding(bottom = 4.dp), TtTheme.type.reading15, TtTheme.colors.textSecondary,
        )
    }
    SwitchRow("Sounds", d.sounds, actions.setSounds)
    SwitchRow("Haptics", d.haptics, actions.setHaptics)
}

// ---------------------------------------------------------------- notifications

@Composable
private fun NotificationsGroup(state: SettingsUiState, actions: SettingsActions) {
    val c = TtTheme.colors
    val words = permissionWords(state.notifications)
    StateLine(words.lamp, words.role, "Notifications: ${words.state}", words.sentence)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.notifications != PermissionState.GRANTED) {
            PixelButton("ALLOW", actions.allow, Modifier.weight(1f), kind = ButtonKind.PRIMARY, style = TtTheme.type.label11Bold)
        }
        PixelButton("PHONE SETTINGS", actions.openPhoneSettings, Modifier.weight(1f), style = TtTheme.type.label11Bold)
    }
    state.pushRegistration?.let { push ->
        DashedDivider()
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("PUSH REGISTRATION", color = c.textSecondary, bold = true, size = LabelSize.L10)
            PixelText(push, style = TtTheme.type.reading15, color = c.textReading)
        }
    }
    DashedDivider()
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("TIP", color = if (state.batterySaver) c.caution else c.textSecondary, bold = true, size = LabelSize.L10)
        PixelText(
            (if (state.batterySaver) BATTERY_SAVER_ON else "") + BATTERY_TIP,
            style = TtTheme.type.reading15, color = c.textReading,
        )
    }
    DashedDivider()
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PixelText(
            "Send a test notification through one of your watches to check that alerts reach this phone.",
            style = TtTheme.type.reading15, color = c.textReading,
        )
        PixelButton(
            if (state.test.sending) "SENDING..." else "SEND TEST", actions.sendTest,
            Modifier.fillMaxWidth(), enabled = !state.test.sending, kind = ButtonKind.SECONDARY,
        )
        state.test.outcome?.let { outcome ->
            val ok = outcome == TestOutcome.SENT
            val text = testWords(outcome, state.test.detail)
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = text },
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.padding(top = 3.dp)) {
                    StatusLamp(
                        if (ok) app.transittracker.ui.format.Lamp.CIRCLE else app.transittracker.ui.format.Lamp.TRIANGLE,
                        if (ok) c.go else c.caution, size = 14.dp,
                    )
                }
                PixelText(text, Modifier.weight(1f), TtTheme.type.row15Strong, if (ok) c.go else c.caution)
            }
        }
    }
}

// ---------------------------------------------------------------- about

@Composable
private fun AboutGroup(state: SettingsUiState, actions: SettingsActions) {
    val c = TtTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = TtTheme.spacing.touch).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelText("App version")
        PixelText(state.appVersion, style = TtTheme.type.row15Strong, color = c.textSecondary)
    }
    UpdateRows(state.update, actions.updateApp)
    DashedDivider()
    if (state.accountsAvailable) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("ACCOUNT", color = c.textSecondary, bold = true, size = LabelSize.L10)
            PixelText(state.account ?: "Not signed in", style = TtTheme.type.name17)
            PixelText(ACCOUNT_NOTE, style = TtTheme.type.reading15, color = c.textSecondary)
            state.accountMessage?.let { PixelText(it, style = TtTheme.type.reading15, color = c.stop) }
            if (state.account != null) {
                PixelButton(
                    if (state.signingOut) "SIGNING OUT..." else "SIGN OUT",
                    actions.signOut, Modifier.fillMaxWidth().padding(top = 4.dp),
                    kind = ButtonKind.SECONDARY, enabled = !state.signingOut,
                )
            } else {
                PixelButton(
                    if (state.signingOut) "SIGNING IN..." else "SIGN IN WITH GOOGLE",
                    actions.signIn, Modifier.fillMaxWidth().padding(top = 4.dp),
                    kind = ButtonKind.SECONDARY, enabled = !state.signingOut,
                )
            }
        }
        DashedDivider()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("DEVICE ID", color = c.textSecondary, bold = true, size = LabelSize.L10)
        PixelText(state.deviceId ?: "Not joined yet", style = TtTheme.type.name17)
        PixelText(DEVICE_ID_NOTE, style = TtTheme.type.reading15, color = c.textSecondary)
    }
    NavRow("Font and data licences", "Four fonts, one licence", actions.openLicences)
    if (state.debugBuild) NavRow("Debug gallery", null, actions.openDebugGallery, hint = "FOR DEVELOPERS")
}

/** Under the version: where an app update has got to, and the one button that moves it on. */
@Composable
private fun UpdateRows(words: UpdateWords, onAction: (UpdateAction) -> Unit) {
    val c = TtTheme.colors
    DashedDivider()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (words.lamp != null) {
            StateLine(words.lamp, words.role, words.headline, words.detail)
        } else {
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PixelText(words.headline, style = TtTheme.type.row15Strong)
                words.detail?.let { PixelText(it, style = TtTheme.type.reading15, color = c.textSecondary) }
            }
        }
        words.notes?.let { notes ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("WHAT'S NEW", color = c.textSecondary, bold = true, size = LabelSize.L10)
                PixelText(
                    notes, style = TtTheme.type.reading15, color = c.textReading,
                    maxLines = 6, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        words.button?.let { text ->
            PixelButton(
                text, { onAction(words.action) }, Modifier.fillMaxWidth(),
                kind = if (words.action == UpdateAction.INSTALL) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                enabled = !words.busy,
            )
        }
    }
}
