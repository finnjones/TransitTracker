# Pinned protocol buffer definitions

Kotlin classes are generated from these files by the Wire Gradle plugin in `:server:live`
(`sourcePath { srcDir("../../proto") }`, package `com.google.transit.realtime`). Do not edit them by
hand; replace them with a newer upstream copy and re-run the tests.

| File | Source | Notes |
|---|---|---|
| `gtfs-realtime.proto` | <https://raw.githubusercontent.com/google/transit/master/gtfs-realtime/proto/gtfs-realtime.proto>, fetched 2026-11-11, SHA-256 `8feff2c5499e0ff08777e203e49ef702e07be0a8376b8bb2126add311a709299` | Official GTFS-Realtime spec, unmodified (Apache 2.0) |
| `tfnsw-gtfs-realtime-extension.proto` | Transcribed from public copies of the TfNSW extension file (see below) | TfNSW extension number **1007** |

## TfNSW extension (1007): provenance and TODO

TfNSW publishes its GTFS-R extension `.proto` on the
[Public Transport – Realtime Vehicle Positions v2](https://opendata.transport.nsw.gov.au/data/dataset/public-transport-realtime-vehicle-positions-v2)
dataset page. That site could not be reached from the build environment (proxy 403), so the file was
**not** obtained from TfNSW directly. Instead, only the extension declarations this project reads were
transcribed from two independent public copies that agree on every field number and wire type:

- `jaboles/DVA5` — `PlasmaCommon/src/jb/plasma/gtfs/gtfs-realtime_1007_extension.proto` (a copy of TfNSW's
  own file, which redefines the whole GTFS-R schema with the extensions inline)
- `ksharma-xyz/KRAIL-BFF` — `server/src/main/proto/nsw/tfnsw-gtfs-realtime.proto` (field numbers verified
  empirically against live NSW feeds)

Declared extensions:

| Extends | Field | Type | Used for |
|---|---|---|---|
| `VehiclePosition` | `consist = 1007` (repeated) | `CarriageDescriptor` | car count (`VehiclePosition.carCount`), occupancy fallback (median carriage) |
| `VehicleDescriptor` | `tfnsw_vehicle_descriptor = 1007` | `TfnswVehicleDescriptor` | decoded, not yet used |

Deliberate difference: `CarriageDescriptor.position_in_consist` is `optional` here (TfNSW: `required`),
so one missing value cannot make a whole feed undecodable. The wire format is identical.
Not declared (not needed yet): `FeedEntity.update = 1007` (`UpdateBundle`) and
`Position.track_direction = 1007`.

**TODO (needs network access to TfNSW):**

1. Download the official extension file from the dataset page and diff it against
   `tfnsw-gtfs-realtime-extension.proto`. Field numbers are the contract; names may differ.
2. Per-carriage occupancy is decoded (`CarriageDescriptor.occupancy_status`) but the API has no field
   for it yet (`VehiclePosition`/`TripDelta` only carry `occupancy` and `carCount`); add one to
   `shared/model` `Api.kt` if the front end wants per-car crowding.
3. Record a real Sydney Trains and Metro vehicle positions feed and add it as a replay fixture test.
   (Checked live on 11 Nov 2026: both decode with this file; `consist` gives 8 cars for trains and 6
   for metro. A real Sydney Trains trip update excerpt is in `testdata/realtime/`.)

## Sydney Trains trip updates: StopTimeUpdate field 6

The live `/v2/gtfs/realtime/sydneytrains` feed (11 Nov 2026) sends `StopTimeUpdate` field 6 as a
varint (values 1..3, TfNSW's departure occupancy from before GTFS-R standardised it as field 7),
while `gtfs-realtime.proto` defines 6 as the `stop_time_properties` message, so Wire rejects the
whole feed. Rather than edit the pinned upstream file, `TfnswWireFixes` in `:server:live` renumbers
those fields to 7 at the byte level before decoding. The feed also carries extension 1007 on
`TripUpdate` (strings: a number and the route id) and on `StopTimeUpdate`; they are not declared
here and Wire skips them.

If the extension turns out to be wrong, delete `tfnsw-gtfs-realtime-extension.proto`: the code in
`server/live` (`VehicleMapper`) then falls back to the standard `multi_carriage_details` field only
after removing its two references to `consist`.
