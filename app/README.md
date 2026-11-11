# TransitTracker front end (`/app`)

Compose Multiplatform, pixel-art "pocket platform" look. The spec is
`docs/transittracker-frontend-plan.md` (sections 2, 3, 7, 8); the mockups are the source for
sizes. This file is the practical part: what exists and how to use it.

## Layout

```
app/ui                      :app:ui, package app.transittracker.ui (KMP; JVM target today)
  src/commonMain/kotlin/…/ui/
    theme/       TtTheme, colours (Night/Day), type, the dot, spacing
    components/  PixelText, PixelPanel, PixelButton, switch, stepper, toggles, badges, status, icons
    format/      pure status mapping, time and countdown formatting, line-colour contrast
    sprites/     sprite sheets, the shared frame clock, VehicleSprite, WalkerSprite
    strip/       JourneyStrip (+ its pure maths), LegStrip, grounds
    settings/    UiSettings, SettingsStore, KeyValueSettingsStore
    gallery/     galleries, sample data, the screenshot catalogue, AppShots (transitions), debug screen
    nav/         Route (every screen), Tab, Navigator (+ rememberNavigator, LocalNavigator)
    motion/      PageTransition, Timeline (plan 3.4 timings), TransitionController, TransitionHost, flapRow
    app/         AppDeps (+ LocalAppDeps, fake()), TransitTrackerApp, AppPage, PagePreview,
                 AppController, BottomBar, RouteContent (the one route -> screen map), AlertBadge
    places/      SavedPlace, PlaceStop, TripPlaces, PlacesStore, KeyValuePlacesStore
    platform/    PlatformServices, FakePlatformServices
    planning/    FakeJourneyPlanner (Home -> Bondi Beach sample journeys)
    state/       StateHolder, rememberStateHolder (the state holder convention)
    fake/        FakeSetup (sample trips and places), ScenarioTransitCore (stale / offline previews)
    feature/<area>/  one package per feature area: its screens, state holders and <Area>Shots.kt
  src/commonMain/composeResources/
    font/        Doto Black, Pixelify Sans 400/600/700, Silkscreen 400/700, Jersey 15 (static TTFs)
                 # Pixelify is patched for legibility (B, Z and ( ) redrawn, fi/fl/ff ligatures off) by
                 # tools/fonts/patch_pixelify.py; re-run it if the files are ever replaced
    files/licenses/  the OFL licences of the four faces (ship them with the app)
    drawable/    sprite_<name>.png, _livery.png, _outline.png (generated, do not edit)
  src/jvmMain/   screenshot harness, desktop window (JVM only)
  src/commonTest, src/jvmTest
```

Rules for every file in `commonMain`: no `java.*`, no Material, no hard-coded colours or font
sizes (read them from the theme), sizes that are part of the pixel art in dots (`dot * n`).
Android and iOS targets are not added yet; keep it compiling for them.

## Theme

Wrap everything in `TtTheme`; read tokens through the `TtTheme` object.

```kotlin
TtTheme(settings: UiSettings, systemDark = isSystemInDarkTheme(), systemReduceMotion = false) { … }
TtTheme(colors = NightColors /* or DayColors */, motion = TtMotion(), time = TtTime(), spriteClock = null) { … }

TtTheme.colors   // TtColors: screen, panel, raised, divider, textSecondary, text, textReading,
                 // go, caution, stop, info, lipPrimary, lipSecondary, lit, water; of(ColorRole)
TtTheme.type     // TtType: digits76/60/44/30 (Doto), title22, title20, name17, row15,
                 // row15Strong, reading15 (Pixelify), label10, label10Bold, label11, label11Bold,
                 // label12Bold, label13Bold, section (Silkscreen)
TtTheme.dot      // Dot: px (device px per dot, 2 dp snapped), dp, `dot * n` -> Dp, px(n)
TtTheme.spacing  // xxs 2, xs 4, s 8, m 12, l 16, xl 24, gutter 16, touch 48 (dp)
TtTheme.motion   // reduceMotion, haptics, sounds
TtTheme.time     // clockFormat (H24 / H12), timeZone
TtTheme.spriteClock  // the shared 8 fps clock (read .tick only inside draw code)
```

Line colours are not tokens: take them from `Route.color` with `parseLineColor(hex) ?: DefaultLineColor`.
Status colour is chosen by role (`TtTheme.colors.of(presentation.role)`), never by line colour.

## Components (`app.transittracker.ui.components`)

```kotlin
PixelText(text, modifier, style = type.row15, color = colors.text, strike = false, textAlign, maxLines, overflow)
                 // numerals: every digit in a Pixelify or Silkscreen style is drawn in Jersey 15
                 // automatically (Pixelify's 5 and Silkscreen Bold's 4 are hard to read); pass plain strings
Label(text, modifier, color, bold = false, size = LabelSize.L11)      // Silkscreen 10/11/12/13
SectionLabel(text)                                                   // "TO WORK", a heading
Digits(text, modifier, style = type.digits30, color)                // Doto clock
DashedDivider(modifier, color = divider); SolidDivider(...)

PixelPanel(modifier, selected = false, borderColor, background, contentPadding = 12.dp, verticalSpacing = 8.dp) { … }
DialogBox(title, modifier, body = null, actions = { PixelButton(…, Modifier.weight(1f)) … }, content = null)
DialogScrim(onDismiss) { DialogBox(…) }
Modifier.pixelBorder(color, dots = 1); Modifier.pixelFill(color, dots = 1)   // four bars, no corners

PixelButton(text, onClick, modifier, kind = ButtonKind.SECONDARY, enabled = true, style = type.label12Bold)
PixelButton(onClick, modifier, kind, enabled, faceHeight = 48.dp, contentDescription) { contentColor -> … }
PixelIconButton(icon, contentDescription, onClick, modifier, kind = ButtonKind.FLAT, enabled, iconSize)
  // PRIMARY / SECONDARY have the lip and sink 2 dots when pressed (+ haptic tick); FLAT has no lip.
  // Footprint: faceHeight + 4 dots (PRIMARY/SECONDARY), faceHeight + 2 dots (FLAT).

PixelTextField(value, onValueChange, label /* spoken name, not drawn */, modifier, placeholder = "",
               input = FieldInput.NAME /* NAME | SEARCH | CODE | URL: picks the keyboard */, enabled = true,
               height = 48.dp, style = type.name17, onSubmit = null, focusRequester = null,
               leading = null, trailing = null)
  // The only text input. Single line; border is Divider idle, Text with focus; digits are set in
  // the numeral face as in PixelText. Do not use BasicTextField in screens.

PixelSwitch(checked, modifier, onCheckedChange = null)   // visual; with a callback it is a 48 dp target
SwitchRow(label, checked, onCheckedChange, modifier, enabled, divider = true)
StepperButton(icon, label, onClick, modifier, enabled)
Stepper(value, onDecrement, onIncrement, decrementLabel, incrementLabel, modifier, valueStyle = type.title20, canDecrement, canIncrement)
StepperRow(label, value, onDecrement, onIncrement, decrementLabel, incrementLabel, …, labelWidth = 92.dp)
DayToggle(letter, selected, onSelectedChange, contentDescription, modifier)
DayToggleRow(selectedDays: Set<Int> /* ISO, 1 = Monday */, onChange)
ToggleChip(text, selected, onSelectedChange, modifier)

RouteBadge(name, lineColor: Color, modifier, feedTextColor = null, style = BadgeStyle.NORMAL, width = null)
RouteBadge(name, colorHex: String?, textColorHex: String?, …)       // text colour by contrast (plan 2.4)
StatusLamp(lamp, color, modifier, size = 14.dp)
StatusLine(status: StatusPresentation, modifier, size = StatusSize.NORMAL, suffix = null)
PlatformSign(platform, changed, modifier)        // changed: NOW PLAT, blinks 1 Hz for 5 s, then inverted
SeatPips(occupancy, modifier, showWords = true)  // nothing for UNKNOWN
CarCount(cars, modifier, diagram = true)
Countdown(text: CountdownText, modifier, size = CountdownSize.XL76, color)
CountBadge(count)
DataChipBox(kind: DataChipKind /* LIVE | STALE | TIMETABLE | OFFLINE */, ageMs = null, modifier)   // header chip
PixelIcon(icon: PixelIconData, modifier, tint, size: Dp? = null /* null: 1 dot per cell */, contentDescription = null)
PixelIcons.{LampCircle, LampTriangle, LampCross, LampPlus, LampRing, Plus, Minus, Swap, Locate,
            ChevronLeft, ChevronRight, ChevronUp, ChevronDown, Train, MapPin, Warning, Bell, Sliders, Info, House, Briefcase, Pin}
```

Every row that shows a service should be one accessibility item read as a sentence (plan 7);
`StatusLine`, `PlatformSign`, `SeatPips`, `CarCount` and `Countdown` already expose their spoken
words, sprites are hidden from the reader.

## Status and time (`app.transittracker.ui.format`, pure, tested)

```kotlin
statusPresentation(status, delaySeconds = null, stale = false, dataAgeMs = null): StatusPresentation
statusPresentation(departure: Departure)
  // lamp, words ("4 MIN LATE"), role, strikeScheduledTime, strikeRow, countdownPrefix ("~" if stale), spoken
platformPresentation(platform, changed); occupancyPresentation(occupancy); carCountWords(n)
formatClock(epochMs, zone, format = ClockFormat.H24, withSuffix = true)   // "07:42" / "7:42 AM"
formatDayDate(epochMs, zone)                                               // "WED 11 NOV"
countdown(targetMs, nowMs, zone, format, prefix = "", verb = "leaves"): CountdownText
  // < 1 min "NOW"; up to 60 min "4" + "MIN"; beyond, the clock time
countdownMmSs(targetMs, nowMs)                                             // "04:59" (service / tracking only)
badgeTextColor(lineColor, feedTextColor); contrastRatio(a, b); parseLineColor(hex)
```

Pass `TtTheme.time.timeZone` and `TtTheme.time.clockFormat` to the time functions.

## Sprites (`app.transittracker.ui.sprites`)

```kotlin
VehicleSprite(mode, liveryColor, state = VehicleState.Moving, facing = Facing.RIGHT, scale = 1, modifier, long = false)
vehicleState(status, stale, delaySeconds, atStop): VehicleState   // Moving, AtStop, Late(min), Ghost, Stale, Cancelled
WalkerSprite(pose = WalkerPose.STAND /* WALK, RUN */, modifier, facing, scale = 1)
```

- `scale` is whole dots per art pixel (1 = cards and strips, 2 = galleries / page transition).
  A train at scale 1 is 64 x 32 dp including its 1-dot padding. Drawing is nearest-neighbour.
- All sprites animate from one clock (`TtTheme.spriteClock`, about 8 fps) and hold a still frame
  when `TtTheme.motion.reduceMotion` is on. The Late "+4" tag overhangs the top by 16 dp.
- Day theme draws the 1-dot outline automatically. `long = true` gives the four-car train / metro
  and coupled light-rail pair for the page transition. `Mode.OTHER` is a plain arrow.
- Lower level: `SpriteSheet` (sizes, ground row, body extent), `rememberSpriteImages(sheet)`,
  `DrawScope.drawSpriteFrame(...)` for drawing sprites inside your own Canvas.
- To change the art: edit `tools/sprites/make_sprites.py`, then
  `python3 tools/sprites/make_sprites.py --app app/ui/src/commonMain/composeResources/drawable`
  (needs Pillow). Each app sheet is base + livery mask + outline, frames 0-3 moving, 4 stopped;
  the walker has walk 0-3, stand 4, run 5-8. If a frame size changes, update `SpriteSheet`.

## Strips (`app.transittracker.ui.strip`)

```kotlin
JourneyStrip(calls, yourStopId, destinationStopId, mode, liveryColor, status, clock: WallClock, modifier,
             stale = false, delaySeconds = null, lateTag = true)
JourneyStrip(detail: ServiceDetail, yourStopId, destinationStopId, clock, modifier, lateTag = true)
LegStrip(legs: List<Leg>, modifier, sprites = false)   // Leg.Walk(min), Leg.Ride(min, mode, lineName, lineColor, feedTextColor)
```

- `JourneyStrip` is 54 dp high and fills the width. Feed it the `calls` of the headline service
  from `TransitCore.service(ref)` (subscribe only while the card is on screen) and the core's
  clock. It re-places the vehicle every tick, steps to new estimates over 1 s, uses the ghost
  sprite for TIMETABLED, and carries the screen-reader sentence. Pass `lateTag = false` when the
  delay is already written beside it, or leave 16 dp free above it.
- The maths are pure and tested: `vehicleProgress(calls, nowMs)`, `journeyStripModel(...)`,
  `legSpans(...)`, `legSentence(...)`.
- `LegStrip` is 62 dp high with sprites, 32 dp without.

## Settings (`app.transittracker.ui.settings`)

```kotlin
interface SettingsStore { val settings: Flow<UiSettings>; suspend fun current(); suspend fun update(change) }
KeyValueSettingsStore(kv: KeyValueStore)   // one key per setting: ui.theme, ui.clockFormat, …
UiSettings(theme = NIGHT, clockFormat = H24, cardDensity = COMFORTABLE, reduceMotion = false, sounds = false, haptics = true)
```

Use the same `KeyValueStore` the core gets in `CoreDependencies`; `InMemoryKeyValueStore()` for previews.

## Navigation (`app.transittracker.ui.nav`)

Every screen is a `Route` (serializable data, saved with the back stack; never put live data in it):

| Route | Parameters | Screen (file under `ui/`) |
|---|---|---|
| `Trips` (tab) | | `feature/home/HomeScreen.kt` |
| `MapTab` (tab) | | `feature/map/MapScreen.kt` |
| `Alerts` (tab) | | `feature/alerts/AlertsScreen.kt` |
| `Notify` (tab) | | `feature/notify/NotifyScreen.kt` |
| `Settings` (tab) | | `feature/settings/SettingsScreen.kt` |
| `TripTimetable` | `tripId`, `serviceDate: ServiceDate? = null` (today) | `feature/timetable/TripTimetableScreen.kt` |
| `Service` | `ref: ServiceRef`, `boardStopId?`, `alightStopId?` | `feature/service/ServiceScreen.kt` |
| `Tracking` | `ref`, `boardStopId?`, `alightStopId?` | `feature/map/TrackingScreen.kt` |
| `PlanJourney` | `fromPlaceId?`, `toPlaceId?`, `fromOnboarding = false`, `from: JourneyEnd?` (a start that is not a saved place: a station tapped on the map) | `feature/plan/PlanJourneyScreen.kt` |
| `JourneyOptions` | `from: JourneyEnd`, `to: JourneyEnd`, `timeMs?`, `timeKind`, `fromOnboarding` | `feature/plan/JourneyOptionsScreen.kt` |
| `JourneyDetail` | `from`, `to`, `itinerary: Itinerary`, `fromOnboarding` | `feature/plan/JourneyScreen.kt` |
| `SavedPlaces` | | `feature/places/PlacesScreen.kt` |
| `PlaceEdit` | `placeId?` (null = new) | `feature/places/PlaceEditScreen.kt` |
| `ManageRoutes` | | `feature/places/ManageRoutesScreen.kt` |
| `EditRoute` | `tripId` | `feature/places/EditRouteScreen.kt` |
| `WatchEditor` | `watchId?` (null = new), `fromTripId?`, `fromService?`, `boardStopId?`, `alightStopId?` | `feature/notify/WatchEditorScreen.kt` |
| `Onboarding` | | `feature/onboarding/OnboardingScreen.kt` |
| `DebugGallery` | | `gallery/DebugGalleryScreen.kt` |

`JourneyEnd(name, position, plannerPlaceId?, savedPlaceId?)` is one end of a planned journey;
`toPlanEndpoint()` makes the `PlanEndpoint`. `app/RouteContent.kt` maps routes to screens; do not
edit it, keep your screen's signature.

```kotlin
val nav = LocalNavigator.current
nav.push(route, transition = null)        // on top of the current page (current tab's stack)
nav.replace(route, transition = null)
nav.back(): Boolean                       // pop; on another tab's root -> Trips; false on the Trips root
nav.popToRoot(); nav.switchTab(tab)       // each tab keeps its stack; tapping the current tab pops to root
nav.openFromNotification(route)           // cut; a non-tab route goes above the Trips root
nav.resetTo(Tab.TRIPS, PageTransition.Doors)   // onboarding finished: Home behind the doors
nav.startOnboarding()                     // the shell's gate (not for screens)
nav.current / backStack / currentTab / canGoBack   // snapshot state
```

Page state: anything in `rememberSaveable` inside a page survives being covered, tab switches and
process death (the host keys it by `NavEntry.id`); it is dropped when the page leaves every stack.
A covered page is not composed, so its state holder stops and is rebuilt on return.
System back: the platform shell calls `AppController.onBack()` (Compose Multiplatform 1.12 has no
back handler on the common compile classpath here); Escape does it in the desktop window.
A page that shows something of its own that back should close first (a search view, a sub-page)
says so with `OnBack(enabled = …) { close it }`; while enabled, back runs that instead of leaving
the page. Do not call `nav.back()` inside it.
The shell's other hooks: `AppController.onForeground()`, `onBackground()`,
`openFromNotification(data)`.

## Page transitions (`app.transittracker.ui.motion`)

| Kind | `PageTransition` | Default for |
|---|---|---|
| Vehicle pass | `PageTransition.vehiclePass(mode, colorHex)` / `vehiclePass(mode, Color)` | `push` (a list opens a detail). Pass the service's own vehicle (or the journey's first ride); without one a plain train in the default line colour crosses |
| Doors | `PageTransition.Doors` | `push` to or from `MapTab` / `Tracking`; tab switches with the map on either side |
| Split-flap | `PageTransition.SplitFlap` | `switchTab` |
| Cut | `PageTransition.Cut` | `openFromNotification`, `startOnboarding` |

`back()` plays the transition that brought the page in, in reverse (the vehicle runs right to
left). Timings follow the "Page transitions" artboard (`Timeline`): 20 ms steps; the wipe uncovers
the page in 300 ms with the vehicle's nose on its edge (four cars clear in about 640 ms, a bus in
about 400 ms); doors take 600 ms in five steps and swap the page at 300 ms; flap rows take 280 ms in
five steps, 60 ms apart, all done by 500 ms. The new page takes touches by 300 ms whatever the
decoration is doing; a tap during a transition finishes it (swallowed only before 300 ms); a
navigation during a transition finishes it and is itself a cut; reduce motion or battery saver
makes every transition a cut.

**Split-flap rows.** Mark the rows of your page from the top, starting at 0:

```kotlin
Row(Modifier.fillMaxWidth().flapRow(1)) { … }
```

`PageHeader` is row 0. Unmarked content appears at once. `flapRow` only reads a clock in the layer
phase, so it costs nothing when no transition runs; it works inside `LazyColumn` items.
`TransitionPreview(change, elapsedMs, widthDp, from, to)` draws one frozen frame (filmstrips).

## What screens may depend on (`AppDeps`, `LocalAppDeps.current`)

```kotlin
class AppDeps(core: TransitCore, planner: JourneyPlanner, settings: SettingsStore,
              places: PlacesStore, platform: PlatformServices, clock: WallClock,
              kv: KeyValueStore? = null /* for screen-level things that should survive a restart */)
AppDeps.fake(clock = SampleData.clock, joined = true, settings = UiSettings(), trips = FakeSetup.trips,
             withPlaces = true, scenario = CoreScenario(), platform = FakePlatformServices(), kv = null)
```

- `core`: the `TransitCore` contract (trips, departures, `service(ref)`, alerts, data status, watches).
- `planner`: `JourneyPlanner` from `Planning.kt`: `searchPlaces(query, near, limit): PlacesResult`,
  `plan(PlanRequest): PlanResult`. `FakeJourneyPlanner` answers every plan with the Home -> Bondi
  Beach journeys of the Options mockup, timed from the request (`answer` switches to OFFLINE,
  UNAVAILABLE, NOTHING_FOUND or FAILED; `requests` records what was asked).
- `settings`: `SettingsStore` (above).
- `places`: `PlacesStore`, phone only:
  ```kotlin
  val places: Flow<List<SavedPlace>>; val tripPlaces: Flow<Map<String, TripPlaces>>
  fun place(id): Flow<SavedPlace?>; suspend fun currentPlaces(); savePlace(p); deletePlace(id)
  suspend fun reorderPlaces(ids); linkTrip(tripId, fromPlaceId?, toPlaceId?); unlinkTrip(tripId)
  SavedPlace(id, name, icon: PlaceIcon /* HOME, WORK, PIN */, address, position, stops: List<PlaceStop>)
  PlaceStop(stopId, name, walkMinutes, distanceMeters, enabled = true, estimated = false)
  TripPlaces(tripId, fromPlaceId?, toPlaceId?)
  ```
  `KeyValuePlacesStore(kv)` stores JSON under `places.list` and `places.tripLinks`.
- `platform`: `PlatformServices`: `locationPermission`, `requestLocationPermission()`,
  `currentLocation()`, `notificationPermission`, `requestNotificationPermission()`,
  `openNotificationSettings()`, `openUrl(url)`, `appVersion`, `isDebugBuild` (false unless the
  shell says otherwise; shows developer-only rows), `deviceId`, `systemReduceMotion`,
  `batterySaver` (StateFlows; `PermissionState` GRANTED / NOT_ASKED / DENIED).
  `FakePlatformServices` records calls and lets tests set every state.
- `clock`: "now" for every screen.

Fake data: `FakeSetup` saves the Main mockup's trips (Strathfield -> Central linked Home -> Work,
Chatswood -> Tallawong, Central -> Strathfield, Town Hall -> Tallawong via Chatswood) and the Places
mockup's Home, Work and Gym. `CoreScenario(live = LIVE / STALE / OFFLINE, statusMessage,
packDownloading, packOutOfDate, noTrips)` re-labels the fake core's output the way the real core
presents those situations (`ScenarioTransitCore`, a `by`-delegating wrapper, not a core).

## State holders (`app.transittracker.ui.state`)

One plain class per screen in its feature package, `XxxStateHolder(only the deps it needs, scope)`,
extending `StateHolder<XxxUiState>`: one `val state: StateFlow<XxxUiState>` (an immutable data class
with everything the screen draws) and plain methods for events. Navigation stays in the screen.
Pure rules go in top-level functions beside it (tested without coroutines).

```kotlin
val deps = LocalAppDeps.current
val holder = rememberStateHolder(tripId) { scope -> TimetableStateHolder(deps.core, tripId, scope) }
val state by holder.state.collectAsState()
```

`rememberStateHolder` gives each holder its own scope, cancelled when the page leaves the
composition. Tests build the holder from `AppDeps.fake()` pieces with `runTest`'s `backgroundScope`
(see `HomeStateHolderTest`). No Android ViewModel. Example: `feature/home/HomeStateHolder.kt` +
`HomeModel.kt` (pure rules).

## Rules for feature work (parallel agents)

You own `feature/<your area>/` and nothing else: your screens (keep the placeholder's function
name and signature, `RouteContent.kt` calls it), your state holders, helpers and `<Area>Shots.kt`.
Do not edit `nav/`, `motion/`, `app/`, `state/`, `places/`, `platform/`, `planning/`, `fake/`,
`gallery/`, the design system or `build.gradle.kts`; if you need something there, say so in your
report. Areas: `home`, `timetable`, `service`, `plan` (plan, options, journey), `places` (places,
place edit, manage routes, edit route), `alerts`, `notify` (list, watch editor), `map` (map tab,
tracking), `onboarding`, `settings`. Pushed pages start with `PageHeader(title, onBack =
{ nav.back() })`; tab roots draw only the area above the bottom bar. The shell provides
`LocalAppDeps`, `LocalNavigator` and the theme.

## Screenshots

```
./gradlew :app:ui:screenshots                       # all shots
./gradlew :app:ui:screenshots -Pshots=home-night    # some (comma-separated)
./gradlew :app:ui:runDesktop                        # the app on fake data in a window (needs a display)
TT_SHOT_DENSITY=3 ./gradlew :app:ui:screenshots -Pshots=home-night   # at a phone's density -> home-night@3x.png
```

PNGs land in `app/ui/build/screenshots/<name>.png`, 390 x 844 dp at 2x (780 x 1688 px) unless the
shot says otherwise (`Shot(widthDp, heightDp, density)`), rendered off-screen (no display needed);
the harness gives screens a few frames of real time so their flows settle. Every shot gets a fixed
sprite clock (`tick`), Sydney time and the 24 h clock, so output is repeatable. Phones are 2.6x to
3.5x, so small text is sharper on a phone than in the 2x shots; render with `TT_SHOT_DENSITY` when
judging whether lettering reads.

**Adding shots from a feature.** Each area has `feature/<area>/<Area>Shots.kt` with
`val shots: List<Shot>`; add entries there, names starting with your area (`"service-late"`).
`Screenshots.all` gathers every list (and fails on duplicate names). For a whole-app shot use
`AppShot(AppDeps.fake(...), navigator)` (open your page first with
`remember { Navigator().apply { push(route, PageTransition.Cut) } }`); for one page use
`PagePreview(deps) { AppPage(route) }`. Use `SampleData` (fixed "now" of Wed 11 Nov 07:38) or
`AppDeps.fake()` for data. Look at the PNG; compare with the mockup.
`RenderSmokeTest` renders three shots (one is the whole app on fake data) during `./gradlew build`.
