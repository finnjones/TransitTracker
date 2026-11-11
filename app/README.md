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
    gallery/     galleries, sample data and the screenshot catalogue (usable as a debug screen)
  src/commonMain/composeResources/
    font/        Doto Black, Pixelify Sans 400/600/700, Silkscreen 400/700 (static TTFs)
    files/licenses/  the three OFL licences (ship them with the app)
    drawable/    sprite_<name>.png, _livery.png, _outline.png (generated, do not edit)
  src/jvmMain/   screenshot harness (JVM only)
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
PixelIcon(icon: PixelIconData, modifier, tint, size: Dp? = null /* null: 1 dot per cell */, contentDescription = null)
PixelIcons.{LampCircle, LampTriangle, LampCross, LampPlus, LampRing, Plus, Minus, Swap, Locate,
            ChevronLeft, ChevronRight, Train, MapPin, Warning, Bell, Sliders, Info, House, Briefcase, Pin}
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

## Screenshots

```
./gradlew :app:ui:screenshots                       # all shots
./gradlew :app:ui:screenshots -Pshots=card-night    # some (comma-separated)
```

PNGs land in `app/ui/build/screenshots/<name>.png`, 390 x 844 dp at 2x (780 x 1688 px), rendered
off-screen (no display needed). To add one, put a `Shot("my-screen", DayColors) { MyScreen(fake) }`
in `Screenshots.all` (`gallery/Screenshots.kt`); every shot gets a fixed sprite clock (`tick`),
Sydney time and the 24 h clock, so output is repeatable. Use `SampleData` (fixed "now" of
Wed 11 Nov 07:38) or `FakeTransitCore` for data. Look at the PNG; compare with the mockup.
`RenderSmokeTest` renders two shots during `./gradlew build`.
