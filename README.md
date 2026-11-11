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
