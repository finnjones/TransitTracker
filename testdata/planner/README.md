# Trip Planner fixtures

Hand-made answers of TfNSW's Trip Planner API (`/v1/tp/stop_finder` and `/v1/tp/trip`) for
`:server:planner` tests, the end-to-end test and running tt-server locally without a key.

**None of these were recorded from the live API.** They were written by
[make_fixtures.py](make_fixtures.py) (`python3 testdata/planner/make_fixtures.py` rewrites them)
in the shape of:

- the Trip Planner Swagger `tripplanner_v1_swag_efa11_20251002.yml` (Open Data Hub, "Trip Planner
  APIs", 2 Oct 2025, after TfNSW's 27 Aug 2025 upgrade), read from a public mirror; and
- a real `/v1/tp/trip` answer captured in 2026 by the open-source KRAIL app
  (`trip_seven_hills_redfern_715_backtrack.json`, API version 10.6.21.17), which shows fields the
  Swagger leaves out (`RealtimeTripId`, `platformName`, `occupancy`, `realtimeStatus`,
  `footPathInfoRedundant`, `departureTimeBaseTimetable`).

Names, ids, coordinates and times are invented. Replace them with real captures as soon as there
is a key (`tt-admin planner-capture`, below).

## `tfnsw/`: mapping tests

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

The files hold the places asked about: keep them private, or replace the ends before committing.

## What must be checked against the live API (not verified here)

The mapping follows the Swagger and KRAIL's capture; no call was made from this repository. Once a
key is available, capture a few answers and check:

1. **Request parameters** are accepted as sent: `outputFormat=rapidJSON`,
   `coordOutputFormat=EPSG:4326`, `type_origin=coord` with `name_origin=LON:LAT:EPSG:4326`,
   `type_origin=any` with a stop_finder id, `depArrMacro`, `itdDate`, `itdTime`,
   `calcNumberOfTrips=6`, `TfNSWTR=true`, `excludedMeans=checkbox` + `exclMOT_<n>=1`, and for
   stop_finder `type_sf=any`, `name_sf`, `TfNSWSF=true`. No `version` parameter is sent.
2. **Coordinates** come back as `[lat, lon]` (`coord`, `coords`).
3. **Times**: `departureTimePlanned` / `arrivalTimePlanned` are UTC `...Z` and match the GTFS
   timetable (otherwise matching rule 2 fails); `*Estimated` is only meaningful with
   `isRealtimeControlled: true`.
4. **Real-time trip ids**: how often `transportation.properties.RealtimeTripId` equals a GTFS
   `trip_id` in our packs (rule 1), per mode; report the matched / unmatched counts from `/status`.
5. **Stop ids**: leg `origin.id` / `destination.id` are GTFS platform ids and `parent.id` the
   station id; stop_finder ids for stations (global `200060` or legacy `10101100`?).
6. **Platforms**: `properties.platformName` is "Platform 2" / "Stand A" / "Wharf 3"; for
   ordinary bus stops it is the stop name (ignored).
7. **Walks**: walking legs use product class 100 (or 99) with `footPathInfo` IDEST; walks between
   rides appear as `footPathInfo` BEFORE/AFTER and `interchange` on rides; what
   `footPathInfoRedundant` means; whether the first and last walks to a coordinate are always
   their own legs.
8. **Cancellations**: the field and value for a cancelled ride (assumed `realtimeStatus`
   containing `TRIP_CANCELLED`, or `isCancelled`).
9. **Occupancy** words (`MANY_SEATS`, `FEW_SEATS`, `STANDING_ONLY`, ...).
10. **Modes to drop**: product classes for cycling, taxi, car and on-demand (`iconId` 23), and
    whether they appear without being asked for.
11. **No journeys** and **errors**: what TfNSW sends (an `error` object, `systemMessages`, HTTP
    status) for no route, a bad place id and a coordinate outside NSW.
12. **Hand checks**: three real journeys against transportnsw.info, including one with a train to
    bus change.
