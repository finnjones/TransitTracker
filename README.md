# TransitTracker

Personal NSW public transport app (Android first, iOS later) built on Transport for NSW open data.
See [docs/transittracker-backend-plan.md](docs/transittracker-backend-plan.md) for the backend design.

## Modules

| Module | What it is |
|---|---|
| `shared:model` | Contracts: domain types, HTTP API payloads, `TransitCore` (front-end contract), `Timetable` interface, `ServiceTime` (the only GTFS time maths) |
| `shared:timetable` | Timetable pack schema (SQLDelight), pack reader implementing `Timetable` |
| `shared:core` | `TransitCore` implementation for phones: API client, polling, live overlay, pack updater, fake |
| `server:spi` | Server-internal interfaces (`LiveData`, `TimetableRepository`, `WatchService`, ...) and `ServerConfig` |
| `server:pipeline` | Nightly GTFS fetch/validate and timetable pack builder |
| `server:live` | Fetch-on-demand GTFS-Realtime cache, decoding and matching |
| `server:watch` | Push watch engine and FCM sender |
| `server:app` | Ktor HTTP API, SQLite state, invite codes, `tt-admin`, wiring |

Shared modules are Kotlin Multiplatform with a JVM target today; Android and iOS targets are added with the front end.

## Build

```bash
./gradlew build
```

Requires JDK 21. Secrets (TfNSW API key, Firebase service account) are read from the environment on the server only; never commit them.

## Run it locally

No TfNSW key or Firebase project needed: import the synthetic fixture as the `syd-rail` pack,
create an invite and start the server (push messages are only logged without Firebase).

```bash
./gradlew :server:app:installDist
export TT_DATA_DIR=$PWD/data TT_PORT=8080          # data/ is git-ignored
bin=server/app/build/install/tt-server/bin
$bin/tt-admin import-gtfs syd-rail testdata/gtfs/mini-rail   # a GTFS zip works too
$bin/tt-admin invite                                         # prints a one-time code
$bin/tt-server &                                             # Ctrl-C / kill to stop

curl localhost:8080/health
# {"ok":true,"timetableLoaded":true,"timetableVersion":"..."}
curl -X POST -H 'Content-Type: application/json' -d '{"inviteCode":"<code>"}' localhost:8080/v1/join
curl -H 'Authorization: Bearer <deviceToken>' localhost:8080/v1/config
```

Live data stays empty without `TFNSW_API_KEY`. To try it offline, point `TT_REPLAY_DIR` at
GTFS-Realtime files named `<feed>-<kind>.pb` (e.g. `sydneytrains-tripupdates.pb`) or at a copy
of a server's `data/recordings`; they are served instead of calling TfNSW. All settings are in
[deploy/.env.example](deploy/.env.example); `server/app/src/test/.../EndToEndTest.kt` runs the
whole system (pipeline, live, watch engine, API and the phone engine) against the fixture.
