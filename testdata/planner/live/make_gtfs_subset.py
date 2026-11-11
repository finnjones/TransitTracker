#!/usr/bin/env python3
"""Cuts the GTFS records the live Trip Planner captures in this directory refer to out of the
real TfNSW bundles of 11 Nov 2026, into gtfs/ (a GTFS directory tt-server can build a pack from).

    python3 make_gtfs_subset.py <sydneytrains.zip> <metro.zip>

Inputs: /v1/gtfs/schedule/sydneytrains and /v2/gtfs/schedule/metro (tt-server archives them under
data/gtfs/). Kept: every trip whose id is a `RealtimeTripId` in a trip-*.json capture, with its
stop times, services and route; every stop those trips call at, every stop a capture's legs name
(origin, destination, parent) and their stations. Shapes are left out. First input wins on ids
both feeds define (the merger reconciles them the same way).
"""
import csv, glob, io, json, os, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "gtfs")


def rows(z, name):
    if name not in z.namelist():
        return [], []
    r = csv.DictReader(io.TextIOWrapper(z.open(name), encoding="utf-8-sig"))
    return r.fieldnames, list(r)


def main(zips):
    trip_ids, stop_ids = set(), set()
    for path in glob.glob(os.path.join(HERE, "trip-*.json")):
        for journey in json.load(open(path)).get("journeys") or []:
            for leg in journey["legs"]:
                rt = ((leg.get("transportation") or {}).get("properties") or {}).get("RealtimeTripId")
                if rt:
                    trip_ids.add(rt)
                for end in (leg.get("origin") or {}, leg.get("destination") or {}):
                    stop_ids.update(x for x in (end.get("id"), (end.get("parent") or {}).get("id")) if x)

    tables = {}
    for path in zips:
        z = zipfile.ZipFile(path)
        _, trips = rows(z, "trips.txt")
        kept = [t for t in trips if t["trip_id"] in trip_ids]
        ids = {t["trip_id"] for t in kept}
        services = {t["service_id"] for t in kept}
        routes = {t["route_id"] for t in kept}
        for t in kept:
            t["shape_id"] = ""
        _, times = rows(z, "stop_times.txt")
        times = [r for r in times if r["trip_id"] in ids]
        stop_ids.update(r["stop_id"] for r in times)
        add(tables, "trips.txt", z, lambda r: r["trip_id"] in ids, "trip_id", kept)
        add(tables, "stop_times.txt", z, None, None, times)
        add(tables, "calendar.txt", z, lambda r: r["service_id"] in services, "service_id")
        add(tables, "calendar_dates.txt", z, lambda r: r["service_id"] in services, None)
        add(tables, "routes.txt", z, lambda r: r["route_id"] in routes, "route_id")
        add(tables, "agency.txt", z, lambda r: True, "agency_id")
        tables.setdefault("_stops", []).append(z)

    # Stops: the ones named, their stations, and every platform of those stations.
    all_stops = {}
    for z in tables.pop("_stops"):
        fields, stops = rows(z, "stops.txt")
        tables.setdefault("stops.txt", [fields, []])
        for s in stops:
            all_stops.setdefault(s["stop_id"], (fields, s))
    wanted = {i for i in stop_ids if i in all_stops}
    wanted |= {all_stops[i][1].get("parent_station") for i in wanted} - {"", None}
    wanted |= {i for i, (_, s) in all_stops.items() if s.get("parent_station") in wanted}
    fields = tables["stops.txt"][0]
    tables["stops.txt"] = [fields, [{k: all_stops[i][1].get(k, "") for k in fields} for i in sorted(wanted)]]

    os.makedirs(OUT, exist_ok=True)
    for name, (fields, data) in tables.items():
        with open(os.path.join(OUT, name), "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore", lineterminator="\n")
            w.writeheader()
            w.writerows(data)
        print(name, len(data))
    missing = sorted(trip_ids - {r["trip_id"] for r in tables["trips.txt"][1]})
    print("real-time trip ids not in the GTFS (ferries, buses):", len(missing))


def add(tables, name, z, keep, key, given=None):
    fields, data = rows(z, name) if given is None else (rows(z, name)[0], given)
    if not fields:
        return
    entry = tables.setdefault(name, [fields, []])
    for f in fields:
        if f not in entry[0]:
            entry[0].append(f)
    seen = {r[key] for r in entry[1]} if key else None
    for r in data:
        if given is None and not keep(r):
            continue
        if key and r[key] in seen:
            continue
        entry[1].append(r)
        if key:
            seen.add(r[key])


if __name__ == "__main__":
    main(sys.argv[1:])
