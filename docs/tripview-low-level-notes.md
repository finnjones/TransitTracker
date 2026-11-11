# TripView Low-Level Notes

> **Reference only.** These are details of TripView's private backend,
> recovered by static analysis. TransitTracker must not call these servers,
> reuse the app's credentials or copy its formats. Keep this file out of any
> public copy of this repo: if the repo is ever made public again, remove this
> file **and** its history.

Source: TripView Lite 4.5.6 (build 689), `lib/arm64-v8a/libtv.so` from
`split_config.arm64_v8a.apk`. Addresses are file virtual addresses in that
library. Nothing here was observed at run time.

## Hosts

| Host | Use |
|---|---|
| `api.tripview.com.au` | Production API |
| `api.dev.tripview.com.au` | Development API |
| `new.tripview.com.au` | Purpose not determined |
| `tripview.com.au/support/`, `dev.tripview.com.au/support/` | Support pages |
| `api.dropboxapi.com`, `content.dropboxapi.com` | Trip sync (user's Dropbox, OAuth2 with refresh tokens) |

## Paths and files

| Path or file | Inferred use |
|---|---|
| `/config?v=3` | App configuration |
| `/svinfo?region=<region>` | Timetable data info for a region (drives patch/download decisions) |
| `/downloads` | Timetable database and patch downloads |
| `/realtime?types=dtva&region=<region>&routes=<routes>` | Real-time bundle, scoped to region and the user's routes |
| `/shape?`, `/lshape?` | Route shapes for maps (`lshape` possibly line shapes) |
| `/log` | Diagnostic log upload |
| `/oauth2/token` | Dropbox token exchange |
| `regions.json`, `alerts.json`, `config.json`, `sync.json`, `alarm.json`, `s.json` | Server documents and/or local state files |
| `-trips.json`, `-trips-tmp.json`, `-trips-server.json` | Local trip storage and sync staging |
| `.tdb` | Per-region timetable database |
| `?product=` | Product/edition parameter |

## Headers

| Header | Direction | Use |
|---|---|---|
| `X-Auth` | Request (real-time) | App authentication token (value not recovered) |
| `X-Time` | Response | Server time, consumed by `TimeSyncProvider` ("Invalid time" on bad values) |
| `If-None-Match` / `ETag` | Request / response (alerts) | Conditional fetch; persisted `etag` and `lastUpdateTime` |
| `Authorization: Bearer` | Request (Dropbox) | Dropbox API |
| `Dropbox-API-Arg` / `Dropbox-API-Result` | Both | Dropbox content API |

## Real-time response

`types=dtva` = **d**elays, **t**ranspositions, **v**ehicles, **a**lerts.
Parse log: "RealTimeRequest: Parsed N delays, N vehicles, N alerts, N
transpositions."

| Field | Type / default | Address |
|---|---|---|
| `pollInterval` | int seconds, default **30** if absent | `0x9daec` (default at `0x9db00`) |
| `trackerPollInterval` | int seconds, default **15** if absent | `0x9db24` (default at `0x9db38`) |
| `timestamp` | data time | `0x9db60` |
| `delays` | array | `0x9dba4` |
| `vehicles` | array | `0x9dd14` |
| `alerts` | array | `0x9de3c` |
| `transpositions` | array | `0x9df48` |

Delay record fields (parsed around `0x9cbd0`–`0x9ce18`): `start`, `date`,
`tripId`, `stopId`, `stale`, `mtype`, `mtol`, `platform`, `offsets`.
`mtype`/`mtol` are inferred to be a match type and match tolerance used when
attaching a live update to a scheduled service.

Request construction (around `0x9d6f0`–`0x9d804`): appends `&region=` and
`&routes=`, logs "RealTimeManager: Requesting …", then adds `X-Auth`.

## Polling schedule

At `0x92ebc`–`0x92f50`:

- next update = last update time + interval, where the interval is either a
  global value or the request's stored `pollInterval`, chosen by a mode check
  (inferred: tracking vs normal);
- the wait is clamped to zero or more, logged as "QueryRequest: Next
  real-time update in N seconds";
- the server-supplied interval is **not capped** by the app.

## Alerts

- Request at `0x47f0c` sends `If-None-Match` with the stored ETag.
- Response at `0x48a64` reads `ETag`; `etag` and `lastUpdateTime` are
  persisted (`0x48154`, `0x48180`).
- Unchanged result logs "AlertManager: Alerts have not changed".

## Timetable updates

`UpdateManager` log sequence: `requestInfo` → "Info request succeeded/failed"
→ "Data request for" → "Starting patch task for" → "Patched"/"Patch".
Info fields: `patchUrl`, `patchSize`, `patchTime`. Patch failure messages
name the database parts: lines, locations, service lists, pattern map,
interchange map. Validation errors: "Unsupported patch version", "Patch
timestamp doesn't match", "Patch missing manifest". zlib is linked.

## Native interface (JNI exports)

`Java_com_grofsoft_tv_Main_init`, `Main_set24HourMode`,
`Controller_init/start/stop/destroy/queryInternal`,
`HttpRequest_handleHeader/handleData/handleCompletion`, `Timer_run`,
`JNI_OnLoad`.

## Not recovered

`X-Auth` token value or derivation; `.tdb` and patch binary formats; the
journey search algorithm; server-side rate limits; how the backend sources
TfNSW data.
