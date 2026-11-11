# Synthetic GTFS fixtures

Hand-made GTFS static data for tests. Nothing here is real TfNSW data: names and
coordinates are Sydney-like so results look plausible, but IDs, times and calendars are
invented. Use these fixtures for end-to-end tests (for example, synthetic GTFS-Realtime
against a pack built from `mini-rail`).

## `mini-rail/`

Plain GTFS text files (no zip). Two agencies, two routes, 6 stations, 18 trips, valid
2026-01-01 to 2027-12-31, time zone `Australia/Sydney`. `feed_info.feed_version` is
`mini-rail-1`.

### Building a pack from it

```kotlin
// Server side (also what tt-admin will call):
repository.importLocal("mini-rail", listOf(Paths.get("testdata/gtfs/mini-rail")))

// Or directly, with a fixed build date (the service window is build date - 1 day to + 70 days):
val report = ValidationReport.Builder()
val feed = GtfsBundle.open(Paths.get("testdata/gtfs/mini-rail")).use { GtfsLoader.load(it, report) }
val request = PackBuildRequest("mini-rail", "20261111T0300", LocalDate.of(2026, 11, 11))
val built = PackBuilder.buildContent(feed, request, PackIdMaps())
val files = PackBuilder.writeArtifacts(built, request, outDir)
val timetable = JvmTimetables.open(files.sqlite)
```

To query DST days or other dates outside 2026-11-10..2027-01-19, build with a build date
just before them (for example `LocalDate.of(2026, 10, 3)` for 2026-10-04).
`:server:pipeline` tests read the fixture through the `tt.testdata` system property.
Built with zstd level 19, the pack is about 3.5 kB compressed (72 kB SQLite).

### Agencies and routes

| route_id | agency_id | short name | route_type | Mode | Colour |
|---|---|---|---|---|---|
| `T1` | `ST` (Mini Trains) | T1 | 2 | TRAIN | F99D1C |
| `M1` | `SM` (Mini Metro) | M1 | 401 | METRO | 168388 |

### Stations and platforms

Every station is a parent stop (`location_type=1`, `wheelchair_boarding=1`). Platforms are
children (`location_type=0`) named `<station name> Platform N`. Platforms leave
`wheelchair_boarding` empty and so inherit "accessible" from the station, except **`THL_P2`**,
which is explicitly not accessible (`2`).

| Station | Name | Train platforms (north / south) | Metro platforms (north / south) |
|---|---|---|---|
| `CEN` | Central Station | `CEN_P1` / `CEN_P2` | `CEN_M1` / `CEN_M2` |
| `THL` | Town Hall Station | `THL_P1` / `THL_P2` | |
| `WYN` | Wynyard Station, York St (quoted name with a comma) | `WYN_P1` / `WYN_P2` | |
| `MPL` | Martin Place Station | | `MPL_P1` / `MPL_P2` |
| `NSY` | North Sydney Station | `NSY_P1` / `NSY_P2` | |
| `CHA` | Chatswood Station | `CHA_P1` / `CHA_P2` | `CHA_M1` / `CHA_M2` |

`platform_code` is `1`/`2` for train platforms and `M1`/`M2` for metro platforms.
Both routes run between Central and Chatswood, so a CEN → CHA query returns trains and
metros (useful for route-filter tests).

### Stopping patterns and running times

Offsets from the trip's first departure (arrival / departure where they differ):

| Pattern | Stops (stop_sequence) |
|---|---|
| T1 north (`direction_id=0`, shape `SH_T1_N`) | `CEN_P1` +0 (1), `THL_P1` +3:00 (2), `WYN_P1` +6:00/+6:30 (3), `NSY_P1` +11:00 (4), `CHA_P1` +20:00 (5) |
| T1 south (`direction_id=1`, no shape) | `CHA_P2` +0 (1), `NSY_P2` +9:00 (2), `WYN_P2` +13:30/+14:00 (3), `THL_P2` +17:00 (4), `CEN_P2` +20:00 (5) |
| M1 north (`direction_id=0`) | `CEN_M1` +0 (**10**), `MPL_P1` +6:00/+6:30 (**20**), `CHA_M1` +15:00 (**30**) |
| M1 south (`direction_id=1`) | `CHA_M2` +0 (**10**), `MPL_P2` +8:30/+9:00 (**20**), `CEN_M2` +15:00 (**30**) |

Metro trips use stop_sequence 10/20/30 (not 1..n): GTFS-R updates must use those values.

### Services

| service_id | Days | Exceptions (`calendar_dates.txt`) |
|---|---|---|
| `WD` | Mon–Fri | removed on Fri 2026-12-25 (`exception_type=2`) |
| `WE` | Sat–Sun | added on Fri 2026-12-25 (`exception_type=1`) |

So Christmas Day 2026 runs the weekend timetable.

### Trips

| trip_id | Route | Service | First departure | Notes |
|---|---|---|---|---|
| `T1.N.0700` | T1 north | WD | 07:00:00 CEN_P1 | |
| `T1.N.0730` | T1 north | WD | 07:30:00 | |
| `T1.N.0800` | T1 north | WD | 08:00:00 | **Restricted**: `THL_P1` `drop_off_type=1` (pick up only), `NSY_P1` `pickup_type=1` (set down only). Its own pattern. |
| `T1.N.1700` | T1 north | WD | 17:00:00 | `WYN_P1` has no times (`timepoint=0`); the builder interpolates 17:07:00 |
| `T1.N.2350` | T1 north | WD | 23:50:00 | arrives CHA 24:10:00 |
| `T1.N.2430` | T1 north | WD | **24:30:00** | after midnight; arrives CHA 24:50:00 |
| `T1.N.2450` | T1 north | WD | **24:50:00** | after midnight; arrives CHA **25:10:00** |
| `T1.S.0715` | T1 south | WD | 7:15:00 CHA_P2 | times written as `H:MM:SS` (single-digit hour) |
| `T1.S.1730` | T1 south | WD | 17:30:00 | |
| `T1.N.WE.0130` | T1 north | WE | 01:30:00 | DST cases, see below |
| `T1.N.WE.0330` | T1 north | WE | 03:30:00 | |
| `T1.N.WE.0900` | T1 north | WE | 09:00:00 | |
| `T1.S.WE.1000` | T1 south | WE | 10:00:00 | |
| `M1.N.0705` | M1 north | WD | 07:05:00 CEN_M1 | |
| `M1.N.0735` | M1 north | WD | 07:35:00 | |
| `M1.N.2440` | M1 north | WD | **24:40:00** | after midnight; arrives CHA_M1 24:55:00 |
| `M1.S.1710` | M1 south | WD | 17:10:00 CHA_M2 | |
| `M1.N.WE.0930` | M1 north | WE | 09:30:00 | |

Weekday trips run on Wednesday **2026-11-11** (`start_date=20261111` in GTFS-R). Its
after-midnight trips (`T1.N.2430`, `M1.N.2440`, `T1.N.2450`) run in the early hours of
Thursday 12 Nov but belong to service date 20261111: for example `T1.N.2430` departs Central
at 2026-11-12 00:30 AEDT = `2026-11-11T13:30:00Z`.

### Daylight-saving days

Weekend trips run on both 2026 change days (both Sundays). GTFS times count from "noon minus
12 hours" of the service date, which is not local midnight on these days (see `ServiceTime`):

| Service date | Change | Reference (GTFS 00:00:00) | `T1.N.WE.0130` departs | `T1.N.WE.0330` departs | `T1.N.WE.0900` departs |
|---|---|---|---|---|---|
| 2026-10-04 | 02:00 AEST → 03:00 AEDT | 2026-10-03T13:00Z (23:00 AEST on the 3rd) | 2026-10-03T14:30Z (00:30 AEST) | 2026-10-03T16:30Z (03:30 AEDT) | 2026-10-03T22:00Z (09:00 AEDT) |
| 2026-04-05 | 03:00 AEDT → 02:00 AEST | 2026-04-04T14:00Z (01:00 AEDT) | 2026-04-04T15:30Z (02:30 AEDT) | 2026-04-04T17:30Z (03:30 AEST) | 2026-04-04T23:00Z (09:00 AEST) |

### Transfers and shape

- `transfers.txt` (`transfer_type=2`): `CEN_P1` ↔ `CEN_M1` and `CEN_P2` ↔ `CEN_M2` 240 s;
  `CHA_P1` ↔ `CHA_M1` and `CHA_P2` ↔ `CHA_M2` 180 s.
- `shapes.txt`: one shape, `SH_T1_N` (15 points, Central to Chatswood), used by every T1
  northbound trip. Packs store it simplified (Douglas–Peucker, 10 m) as an encoded polyline.

### Regenerating

The files are committed as plain text and are the source of truth; edit them directly and
update this README and the tests in `server/pipeline/src/test` (`FixtureDeparturesTest`,
`GtfsValidationTest`, `PackBuilderTest`) that pin trip lists and counts.
