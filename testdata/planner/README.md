# Trip Planner fixtures

Answers of TfNSW's Trip Planner API (`/v1/tp/stop_finder` and `/v1/tp/trip`) for `:server:planner`
tests, the end-to-end test and running tt-server locally without a key:

- [`live/`](#live-real-answers): **real answers**, captured on 11 Nov 2026 (API version 10.6.21.17)
  for public places only, unchanged, with the GTFS records they refer to;
- [`tfnsw/`](#tfnsw-hand-made-mapping-tests) and [`replay/`](#replay-running-without-a-key):
  hand-made answers written by [make_fixtures.py](make_fixtures.py) (`python3
  testdata/planner/make_fixtures.py` rewrites them) in the shape of the Trip Planner Swagger
  `tripplanner_v1_swag_efa11_20251002.yml` and a 2026 capture by the open-source KRAIL app. Their
  names, ids, coordinates and times are invented; they cover cases the live API did not show
  (taxi legs, a `TRIP_CANCELLED` flag, an ambiguous rule-2 match).

## `live/`: real answers

Captured with `tt-admin planner-capture` and the same request parameters tt-server sends
(`TripPlannerRequests`), between 19:00 and 19:20 Sydney time on Wednesday 11 Nov 2026 (journeys
"now" have live data; the others are for Thursday morning). Bodies are exactly as TfNSW sent them:
no request, header or key is in them, and they hold no device identifiers. `LiveCaptureTest` maps
each one against a pack built from [`live/gtfs/`](live/gtfs), the Sydney Trains + Metro records they
refer to, cut from that day's bundles by [make_gtfs_subset.py](live/make_gtfs_subset.py).

| File | Request | What it shows |
|---|---|---|
| `trip-strathfield-macquarie-university.json` | Strathfield Station -> Macquarie University Station (place ids), now | Train -> metro at Epping and Central; change walks only as `footPathInfo` AFTER with `footPathInfoRedundant: true`; an express CCN whose `stopSequence` lists stations it runs through; two on-demand journeys (class 10) |
| `trip-central-bondi-beach.json` | Central Station -> Bondi Beach (POI), now | Train -> bus at Bondi Junction; bus stands whose `parent` is the railway station; final walk legs to the POI |
| `trip-parramatta-manly-wharf.json` | Parramatta (coordinate) -> Manly Wharf, Thu 08:00 | Coordinate start (TfNSW names its address, walk leg), two train changes, ferry (`platformName` "Wharf 3, Side A"); T1 rides past Central (unmatched, see 4) |
| `trip-macquarie-university-opera-house-arrive-by.json` | Macquarie University Station -> Sydney Opera House (POI), arrive by Thu 09:00 | `depArrMacro=arr`; metro + 20-minute walk |
| `trip-strathfield-macquarie-university-no-metro.json` | As the first, metro excluded (`exclMOT_2`), now | No metro legs; train -> bus with a bus planned on its live time |
| `trip-central-lewisham.json` | Central Station -> Lewisham Station, 19:45 | The T2 trip `84-W...` cancelled in the trip update feed at 19:47 is simply absent |
| `trip-no-route.json` | Two points in the sea off Sydney | 200, no `journeys`, `systemMessages` -303 "origin: no it connection" |
| `trip-unknown-place-id.json` | Place id `99999999` | 200, no `journeys`, `systemMessages` -8020 "origin: no matches" |
| `trip-melbourne-walk.json` | Two points in Melbourne | 200, one walk-only journey (no `error`) |
| `stop_finder-strathfield.json` | "strathfield" | 48 locations, alphabetical within type; the station `isBest`, `matchQuality` 986 vs ~240 for streets, suburbs and POIs |
| `stop_finder-central.json` | "central" | Central Station (global id 200060, legacy `stopId` 10101100); "Davistown, Central RSL Wharf" |
| `stop_finder-sydney-opera-house.json`, `stop_finder-manly-wharf.json` | | A POI; a wharf |
| `coord-central.json` | `/v1/tp/coord` at Central Station (151.2065, -33.8840), `type_1=STOP`, 600 m | 16 stops, every one named "undefined, undefined": the name is `properties.STOP_NAME_WITH_PLACE`, the distance `properties.distance`; `productClasses` per stop; stations by GTFS id (`200060`), many bus stops with a `G` in front (`G201081`). Captured later on 11 Nov 2026 (about 23:35), not with `planner-capture` |

## `tfnsw/`: hand-made mapping tests

Sydney-like stations with invented GTFS ids; `PlannerTestTimetable` in
`server/planner/src/test` holds the matching timetable. All on Wednesday 11 Nov 2026.

| File | What it covers |
|---|---|
| `trip_direct_train.json` | Two direct T9 trains (out of order) and a journey that needs a taxi (dropped) |
| `trip_train_change.json` | T9 then T2 at Strathfield; the change walk is `footPathInfo` AFTER + `interchange` on the first ride; the second ride's real-time id is not a GTFS trip (rule 2 match) |
| `trip_train_bus_walks.json` | Walk, T2, two walking legs at Central (merged), bus 333 (not in the timetable), final walk as `footPathInfo` AFTER on the bus |
| `trip_walk_only.json` | One walking leg |
| `trip_cancelled.json` | A ride with `realtimeStatus: ["TRIP_CANCELLED"]` |
| `trip_unmatched.json` | A ride no timetable trip fits (unknown real-time id, no trip at that time) |
| `trip_no_journeys.json` | `journeys: []` with a system message |
| `stop_finder_central.json` | Stop, platform, street, POI, locality and an `unknown` location (dropped) |

## `replay/`: running without a key

`replay/planner/` uses the stations and trips of the [mini-rail](../gtfs/README.md) GTFS fixture,
so a server with that timetable matches the trains:

| File | Answers |
|---|---|
| `trip.json` | Every `/v1/plan`: Eddy Ave → walk → T1 Central → North Sydney → walk (change) → bus 202 → walk → Cammeray; two journeys (T1.N.0700 running 2 min late, matched by its real-time id; T1.N.0730, matched by line and time) |
| `stop_finder.json` | Every `/v1/places`, keeping locations whose name contains each word searched |
| `stop_finder-<query>.json` | (optional) the answer for one search, e.g. `stop_finder-central.json` |

With `TT_REPLAY_DIR=<dir>`, tt-server reads these from `<dir>/planner/` (live feeds still come from
`<dir>` itself). Recorded `trip.json` times are moved by whole weeks to the week of the requested
date, so a weekday journey stays on its weekday and time of day. Replay calls are not counted
against the TfNSW budget.

```sh
./gradlew :server:app:installDist
export TT_DATA_DIR=/tmp/tt-data TT_REPLAY_DIR=testdata/planner/replay
server/app/build/install/tt-server/bin/tt-admin import-gtfs syd-rail testdata/gtfs/mini-rail
server/app/build/install/tt-server/bin/tt-admin invite          # then POST /v1/join for a token
server/app/build/install/tt-server/bin/tt-server
```

## Capturing real answers

On a server with a key, `tt-admin planner-capture` makes one real call (counted in today's
budget) and saves TfNSW's raw answer, without headers or the key:

```sh
./tt-admin planner-capture places "strathfield" /data/captures/stop_finder-strathfield.json
./tt-admin planner-capture plan -33.8718,151.0943 -33.8840,151.2063 --at 2026-11-12T08:00 /data/captures/trip.json
```

The files hold the places asked about: commit only answers for public places (stations,
landmarks), never someone's home. A coordinate start comes back with TfNSW's name for the nearest
address.

## What was checked against the live API (11 Nov 2026)

About 100 TfNSW calls in all: about 40 `/v1/tp/trip` and 23 `/v1/tp/stop_finder` calls (raw, and
the same questions through tt-server's `/v1/plan` and `/v1/places`), the rest live feeds and GTFS. The
ride match rate below is over the 12 journey requests `k01`-`k12` (72 itineraries) planned after
the fixes.

1. **Request parameters: verified.** All accepted as sent: `outputFormat=rapidJSON`,
   `coordOutputFormat=EPSG:4326`, `type_origin=coord` with `name_origin=LON:LAT:EPSG:4326`,
   `type_origin=any` with a stop_finder id (stations, POIs), `depArrMacro=dep|arr`, `itdDate`,
   `itdTime`, `calcNumberOfTrips=6` (TfNSW returns 6 to 13 journeys), `TfNSWTR=true`,
   `excludedMeans=checkbox` + `exclMOT_2=1` (no metro legs came back); stop_finder `type_sf=any`,
   `name_sf`, `TfNSWSF=true`. Without `version`, TfNSW answers version 10.6.21.17.
2. **Coordinates: verified.** `coord` and `coords` are `[lat, lon]`.
3. **Times: verified.** `*TimePlanned` are UTC `...Z` and equal the GTFS times (every Sydney Trains
   and Metro ride that has its trip in the pack matched within 60 s, metro times to the second).
   `*Estimated` is always present; it differs from the plan only with `isRealtimeControlled: true`
   (e.g. a bus planned 19:10, estimated 19:22:24: TfNSW plans connections on live times).
4. **Real-time trip ids: verified.** `RealtimeTripId` equals the GTFS `trip_id` for every Sydney
   Trains and Metro ride (also given as `AVMSTripID` when live); `gtfsTripId` is a planner-internal
   id. Match rate over `k01`-`k12`:

   | Mode | Rule 1 (trip id) | Rule 2 (line + time) | Unmatched |
   |---|---|---|---|
   | Train | 56 | 0 | 18 |
   | Metro | 31 | 0 | 0 |
   | Bus, ferry | - | - | 13 (not in the rail pack) |

   All 18 unmatched trains are rides past Central into the City (T1 Western to Town Hall/Wynyard,
   T9 to Wynyard): Sydney Trains' GTFS splits a through-running train at Central platform 16 into
   two trips of the same block (`198A...` ends 08:29, `198B...` leaves 08:30), while the planner's
   `RealtimeTripId` names only the first. No single trip covers boarding to alighting, so these
   stay unmatched (never guessed); matching them would need a second trip id on the leg (a contract
   change, not made). Rule 2 never had to step in.
5. **Stop ids: verified.** Leg `origin.id`/`destination.id` are GTFS platform ids (`2135233`) and
   `parent.id` the station id (`213510`); stop_finder gives stations their GTFS station id
   (`213510`, `isGlobalId: true`) with the legacy id (`10101206`) in `properties.stopId`. Bus stands
   and wharves at stations have the **railway station as `parent`** (Bondi Junction Stand D ->
   `202210`, Circular Quay Wharf 3 -> `200020`): fixed, a ride's stop takes an id only from timetable
   stops its mode serves, a walk's end only its own id.
6. **Platforms: verified.** `platformName` is "Platform 3" for trains and metro, "Wharf 3, Side A"
   for ferries (fixed: was cut to "Wharf 3"), and the stop's full name for buses ("Bondi Junction
   Station, Stand D", "Glenayr Ave at Hall St"), where the stand comes from the name. Stop names:
   fixed to keep every part but the platform ("Davistown, Central RSL Wharf" was "Davistown";
   "Epping Station, Beecroft Rd" was "Epping").
7. **Walks: verified.** Walking legs are product class 100 (99 never seen) with `footPathInfo`
   IDEST, or BEFORE on the last walk to a POI. A change between rides is given only as
   `footPathInfo` AFTER on the first ride plus `interchange` (type 100), never as a walking leg.
   **`footPathInfoRedundant` is true on those rides** (every Sydney Trains change in the sample),
   so the mapper dropped the walk: fixed, the flag is no longer read (46 of 46 changes now have
   their walk). The first and last walks to a coordinate or POI are always their own legs.
8. **Cancellations: partly verified.** Trips cancelled in the live feed (`84-W...`, T2 19:47 from
   Central) are left out of the planner's answers entirely; no cancelled ride, `TRIP_CANCELLED` or
   `isCancelled` was seen. `realtimeStatus` only ever held `MONITORED`. Still open: whether a ride
   cancelled after a plan was cached is ever flagged.
9. **Occupancy: partly verified.** Seen on leg stops: `MANY_SEATS` (244) and `FEW_SEATS` (4);
   none on CCN intercity trains. Other words (`STANDING_ONLY`, ...) not seen on a Wednesday evening.
10. **Modes to drop: verified for on-demand.** On-demand legs come unasked as product class **10**
    with `iconId` 23 (KRAIL had class 5): dropped (2 of 8 journeys Strathfield -> Macquarie
    University). Cycling, taxi and car were never offered without being asked.
11. **No journeys and errors: verified.** All HTTP 200 without an `error` object: no route ->
    no `journeys` key and `systemMessages` -303; unknown place id -> -8020 "origin: no matches";
    a coordinate outside NSW (Melbourne) -> a walk-only journey. All map to an empty or walk-only
    `PlanResponse` (not an error). stop_finder answers carry a `systemMessages` entry next to valid
    locations (type `error` code -8011 with empty text, or `message` -8010 "input uniquely
    verified"): ignored, as before.
12. **Hand checks: done against the raw answers** (not transportnsw.info, which needs a browser):
    Strathfield -> Macquarie University (CCN 19:03 platform 3 -> Epping 19:14, 2 min walk, M1 19:20
    platform 5 -> 19:23; 8 + 1 stops on the all-stations T9 variant), Central -> Bondi Beach (T4
    platform 24 19:06 -> Bondi Junction 19:19, 3 min walk, bus 379 Stand D 19:22 -> Glenayr Ave
    19:31, 7 min walk), Parramatta -> Manly Wharf (walk, T1 platform 1 08:10 -> Town Hall 08:38,
    walk, T8 platform 6 -> Circular Quay, 4 min walk, F1 Wharf 3 Side A 08:50 -> Manly 09:12) and
    Lindfield -> Crows Nest (T1 -> metro at Chatswood). Times, platforms, change walks, stop counts
    (express trains fixed: only stops with times count), real-time flags and occupancy agree leg by
    leg; `LiveCaptureTest` pins them.

**Place ranking: fixed.** stop_finder's order is alphabetical; ranking by distance first put
"Strathfield College, Haymarket" above Strathfield Station for a phone in the CBD. Now TfNSW's
`isBest`, then stops whose name has every word searched, then distance bands and `matchQuality`.
