#!/usr/bin/env python3
"""Writes the hand-made TfNSW Trip Planner fixtures in this directory (see README.md).

Nothing here is a recorded TfNSW answer. The JSON shape follows the Trip Planner Swagger
(tripplanner_v1_swag_efa11_20251002.yml) and a real /v1/tp/trip answer captured in 2026 by the
open-source KRAIL app (trip_seven_hills_redfern_715_backtrack.json, API version 10.6.21.17);
names, ids, coordinates and times are invented.

Run from anywhere: python3 testdata/planner/make_fixtures.py
"""
import json
import os
import zlib
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
AEDT = timezone(timedelta(hours=11))  # Sydney in November 2026
DAY = (2026, 11, 11)  # a Wednesday
VERSION = "10.6.21.17"


def h(text):
    """A stable small number for an invented id."""
    return zlib.crc32(text.encode())


def t(hhmm, day=DAY):
    """Local Sydney time on DAY -> the API's `YYYY-MM-DDTHH:MM:SSZ`."""
    h, m = hhmm.split(":")[:2]
    s = int(hhmm.split(":")[2]) if hhmm.count(":") == 2 else 0
    local = datetime(*day, int(h), int(m), s, tzinfo=AEDT)
    return local.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def locality(name, pid):
    return {"id": f"placeID:{pid}:1", "name": name, "type": "locality"}


def station_stop(gid, station_gid, station, platform_label, suburb, coord, legacy, platform_code=None,
                 dep=None, dep_est=None, arr=None, arr_est=None, occupancy=None, stand=False):
    """A platform (or bus stand) of a station, as /v1/tp/trip writes leg ends and stopSequence entries."""
    label = f"{station}, {platform_label}"
    stop = {
        "isGlobalId": True,
        "id": gid,
        "name": f"{label}, {suburb}",
        "disassembledName": label,
        "type": "platform",
        "coord": list(coord),
        "niveau": 0,
        "parent": {
            "isGlobalId": True,
            "id": station_gid,
            "name": f"{label}, {suburb}",
            "disassembledName": label,
            "type": "stop",
            "parent": locality(suburb, 95301000 + (h(suburb)) % 9999),
            "properties": {"stopId": legacy},
            "coord": list(coord),
            "niveau": 0,
        },
        "properties": {
            "WheelchairAccess": "true",
            "AREA_NIVEAU_DIVA": "0",
            "stoppingPointPlanned": platform_label,
            "area": "1",
            "platform": platform_code or platform_label.replace(" ", "").upper(),
            "platformName": platform_label,
            "plannedPlatformName": platform_label,
        },
    }
    if occupancy:
        stop["properties"]["occupancy"] = occupancy
    times(stop, dep, dep_est, arr, arr_est)
    return stop


def street_stop(gid, name, suburb, coord, dep=None, dep_est=None, arr=None, arr_est=None):
    """A bus stop that is not part of a station."""
    stop = {
        "isGlobalId": True,
        "id": gid,
        "name": f"{name}, {suburb}",
        "disassembledName": name,
        "type": "platform",
        "coord": list(coord),
        "niveau": 0,
        "parent": {
            "isGlobalId": True,
            "id": "G" + gid,
            "name": f"{name}, {suburb}",
            "disassembledName": name,
            "type": "stop",
            "parent": locality(suburb, 95308000 + (h(suburb)) % 999),
            "properties": {"stopId": "10" + gid},
            "coord": list(coord),
            "niveau": 0,
        },
        "properties": {
            "WheelchairAccess": "true",
            "AREA_NIVEAU_DIVA": "0",
            "stoppingPointPlanned": name,
            "area": "0",
            "platform": "X1",
            "platformName": name,
            "plannedPlatformName": name,
        },
    }
    times(stop, dep, dep_est, arr, arr_est)
    return stop


def address(name, suburb, coord, kind="street", dep=None, arr=None):
    """A journey's own end: where the user starts or wants to be."""
    point = {
        "id": f"streetID:{(h(name)) % 10**9}:0:{(h(suburb)) % 10**8}:-1:{name}:{suburb}:{name}::{name}:2000:ANY:DIVA_STREET",
        "isGlobalId": False,
        "name": f"{name}, {suburb}",
        "disassembledName": name,
        "type": kind,
        "coord": list(coord),
        "niveau": 0,
        "parent": locality(suburb, 95302000 + (h(suburb)) % 999),
        "properties": {},
    }
    times(point, dep, None, arr, None)
    return point


def times(stop, dep, dep_est, arr, arr_est):
    if dep:
        stop["departureTimeBaseTimetable"] = t(dep)
        stop["departureTimePlanned"] = t(dep)
        stop["departureTimeEstimated"] = t(dep_est or dep)
    if arr:
        stop["arrivalTimeBaseTimetable"] = t(arr)
        stop["arrivalTimePlanned"] = t(arr)
        stop["arrivalTimeEstimated"] = t(arr_est or arr)


def seq_entry(stop, dep=None, arr=None):
    """A stopSequence entry: the stop without its leg-end times, with its own times."""
    entry = {k: v for k, v in stop.items() if not k.endswith(("Planned", "Estimated", "BaseTimetable"))}
    if arr:
        entry["arrivalTimePlanned"] = t(arr)
        entry["arrivalTimeEstimated"] = t(arr)
    if dep:
        entry["departureTimePlanned"] = t(dep)
        entry["departureTimeEstimated"] = t(dep)
    return entry


def train(line, long_name, description, headsign, realtime_trip_id):
    return transport(line, long_name, description, headsign, realtime_trip_id, product=(1, "Sydney Trains Network", 1),
                     operator=("x0001", "Sydney Trains"), tid=f"nsw:020{line}: :H:sj2")


def bus(line, description, headsign, realtime_trip_id):
    return transport(line, f"Sydney Buses Network {line}", description, headsign, realtime_trip_id,
                     product=(5, "Sydney Buses Network", 5), operator=("2436", "Transit Systems"),
                     tid=f"nsw:2{line}: :H:sj2", number=line)


def transport(line, long_name, description, headsign, realtime_trip_id, product, operator, tid, number=None):
    cls, pname, icon = product
    return {
        "id": tid,
        "name": long_name,
        "disassembledName": line,
        "number": number or long_name.split(" Network ")[-1],
        "description": description,
        "product": {"id": cls, "class": cls, "name": pname, "iconId": icon},
        "operator": {"id": operator[0], "name": operator[1]},
        "destination": {"id": "10101100", "name": headsign, "type": "stop"},
        "properties": {
            "isTTB": True,
            "tripCode": 541,
            "timetablePeriod": "NSW JP",
            "lineDisplay": "LINE",
            "globalId": "3001",
            "AVMSTripID": realtime_trip_id,
            "RealtimeTripId": realtime_trip_id,
            "gtfsTripId": f"3001.nsw-2-{line}.1.TA.1840.sj2",
        },
        "iconId": icon,
    }


FOOTPATH = {"product": {"class": 100, "name": "footpath", "iconId": 100}, "properties": {"tripCode": 0, "lineDisplay": "LINE"}}


def walk_leg(origin, destination, seconds, metres, position="IDEST", cls=100):
    transportation = json.loads(json.dumps(FOOTPATH))
    transportation["product"]["class"] = cls
    return {
        "duration": seconds,
        "distance": metres,
        "origin": origin,
        "destination": destination,
        "transportation": transportation,
        "stopSequence": [seq_entry(origin), seq_entry(destination)],
        "coords": [origin["coord"], midpoint(origin["coord"], destination["coord"]), destination["coord"]],
        "footPathInfo": [{"position": position, "duration": seconds, "footPathElem": []}],
        "infos": [],
    }


def ride_leg(origin, destination, transportation, stops, realtime=True, status=("MONITORED",), occupancy=None,
             foot_after=None, interchange=None):
    leg = {
        "duration": seconds_between(origin["departureTimePlanned"], destination["arrivalTimePlanned"]),
        "isRealtimeControlled": realtime,
        "realtimeStatus": list(status),
        "origin": origin,
        "destination": destination,
        "transportation": transportation,
        "stopSequence": stops,
        "coords": [s["coord"] for s in stops],
        "properties": {"PlanLowFloorVehicle": "1", "PlanWheelChairAccess": "1"},
        "infos": [],
    }
    if not realtime:
        del leg["realtimeStatus"]
    if occupancy:
        origin["properties"]["occupancy"] = occupancy
    if foot_after:
        leg["footPathInfo"] = [{"position": "AFTER", "duration": foot_after, "footPathElem": []}]
        leg["footPathInfoRedundant"] = False
    if interchange:
        leg["interchange"] = {"desc": "Fussweg", "type": 100, "coords": interchange}
    return leg


def midpoint(a, b):
    return [round((a[0] + b[0]) / 2, 6), round((a[1] + b[1]) / 2, 6)]


def seconds_between(a, b):
    fmt = "%Y-%m-%dT%H:%M:%SZ"
    return int((datetime.strptime(b, fmt) - datetime.strptime(a, fmt)).total_seconds())


def journey(*legs, interchanges=None):
    rides = sum(1 for leg in legs if leg["transportation"]["product"]["class"] not in (99, 100))
    return {"rating": 0, "isAdditional": False, "interchanges": interchanges if interchanges is not None else max(0, rides - 1),
            "legs": list(legs), "daysOfService": {"rvb": "1"}}


def trip_response(*journeys):
    return {"version": VERSION, "systemMessages": [], "journeys": list(journeys)}


def write(path, data):
    full = os.path.join(HERE, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("wrote", path)


# ---------------------------------------------------------------------------------------------
# Mapping fixtures (tfnsw/): Sydney-like stations with invented GTFS ids; the planner tests build a
# small timetable with the same ids (PlannerTestTimetable.kt).

STR = dict(station_gid="213510", station="Strathfield Station", suburb="Strathfield", legacy="10101151")
CEN = dict(station_gid="200060", station="Central Station", suburb="Sydney", legacy="10101100")
EPP = dict(station_gid="212110", station="Epping Station", suburb="Epping", legacy="10101132")
RFN = dict(station_gid="201510", station="Redfern Station", suburb="Redfern", legacy="10101102")
SMH = dict(station_gid="213110", station="Summer Hill Station", suburb="Summer Hill", legacy="10101148")
BUR = dict(station_gid="213410", station="Burwood Station", suburb="Burwood", legacy="10101150")

C = {
    "STR3": (-33.871770, 151.094250), "STR5": (-33.871820, 151.094410), "CEN16": (-33.883970, 151.206840),
    "EPP1": (-33.772640, 151.082210), "RFN10": (-33.891570, 151.198690), "SMH2": (-33.891410, 151.138620),
    "BUR1": (-33.877380, 151.103550), "CENB": (-33.883150, 151.207980),
}


def plat(where, gid, label, key, code=None, **kw):
    return station_stop(gid, where["station_gid"], where["station"], label, where["suburb"], C[key], where["legacy"],
                        platform_code=code, **kw)


def direct_train():
    def one(dep, arr, rt_id, est=None):
        o = plat(STR, "2135233", "Platform 3", "STR3", "STR3", dep=dep, dep_est=est)
        d = plat(CEN, "2000336", "Platform 16", "CEN16", "CEN16", arr=arr, arr_est=est and arr)
        stops = [seq_entry(o, dep=dep), seq_entry(plat(BUR, "2134231", "Platform 1", "BUR1"), arr="08:05", dep="08:05"), seq_entry(d, arr=arr)]
        return ride_leg(o, d, train("T9", "Sydney Trains Network T9 Northern Line", "Hornsby to Gordon via Strathfield",
                                    "Gordon via Central", rt_id), stops, occupancy="MANY_SEATS")
    first = one("08:02", "08:14", "121A.1450.133.8.T.8.86753001")
    first["origin"]["properties"]["occupancy"] = "FEW_SEATS"
    second = one("08:17", "08:29", "121A.1450.133.8.T.8.86753002")
    # A journey that needs a taxi: the app has no taxis, so it is dropped.
    taxi = {
        "duration": 900, "distance": 14000,
        "origin": plat(STR, "2135233", "Platform 3", "STR3", "STR3", dep="08:05"),
        "destination": plat(CEN, "2000336", "Platform 16", "CEN16", "CEN16", arr="08:20"),
        "transportation": {"product": {"class": 105, "name": "Taxi", "iconId": 105}},
    }
    write("tfnsw/trip_direct_train.json", trip_response(journey(second), journey(first), journey(taxi)))


def train_change():
    o1 = plat(EPP, "2121231", "Platform 1", "EPP1", "EPP1", dep="07:45")
    d1 = plat(STR, "2135235", "Platform 5", "STR5", "STR5", arr="08:05")
    leg1 = ride_leg(o1, d1, train("T9", "Sydney Trains Network T9 Northern Line", "Hornsby to Gordon via Strathfield",
                                  "Gordon via Central", "121A.1450.133.8.T.8.86753100"),
                    [seq_entry(o1, dep="07:45"), seq_entry(d1, arr="08:05")],
                    foot_after=180, interchange=[list(C["STR5"]), list(C["STR3"])])
    o2 = plat(STR, "2135233", "Platform 3", "STR3", "STR3", dep="08:12", dep_est="08:13")
    d2 = plat(RFN, "2015140", "Platform 10", "RFN10", "RFN10", arr="08:22", arr_est="08:23")
    # The planner's real-time trip id is not in the timetable: rule 2 (line + time + stops) matches it.
    leg2 = ride_leg(o2, d2, train("T2", "Sydney Trains Network T2 Leppington & Inner West Line", "Leppington to City",
                                  "City via Redfern", "NOT-IN-GTFS.0812"),
                    [seq_entry(o2, dep="08:12"), seq_entry(plat(BUR, "2134231", "Platform 1", "BUR1"), dep="08:15", arr="08:15"), seq_entry(d2, arr="08:22")])
    write("tfnsw/trip_train_change.json", trip_response(journey(leg1, leg2)))


def train_bus_walks():
    walk1 = walk_leg(address("Smith St", "Summer Hill", (-33.894900, 151.137700), dep="07:24"),
                     plat(SMH, "2131232", "Platform 2", "SMH2", "SMH2", arr="07:31"), 420, 480)
    o = plat(SMH, "2131232", "Platform 2", "SMH2", "SMH2", dep="07:31", dep_est="07:33")
    d = plat(CEN, "2000336", "Platform 16", "CEN16", "CEN16", arr="07:43", arr_est="07:45")
    ride1 = ride_leg(o, d, train("T2", "Sydney Trains Network T2 Leppington & Inner West Line", "Leppington to City",
                                 "City via Central", "131B.1201.120.12.K.8.86753200"),
                     [seq_entry(o, dep="07:31"), seq_entry(d, arr="07:43")], occupancy="STANDING_ONLY")
    # The change at Central is two walking legs (platform -> concourse -> bus stand): merged into one.
    concourse = address("Central Station Grand Concourse", "Sydney", (-33.883500, 151.206500), kind="poi", arr="07:47")
    walk2a = walk_leg(plat(CEN, "2000336", "Platform 16", "CEN16", "CEN16", dep="07:43"), concourse, 240, 210, cls=100)
    stand = station_stop("200039", "200060", "Central Station", "Stand B", "Sydney", C["CENB"], "10101100", arr="07:50")
    walk2b = walk_leg(address("Central Station Grand Concourse", "Sydney", (-33.883500, 151.206500), kind="poi", dep="07:47"), stand, 180, 160, cls=99)
    bo = station_stop("200039", "200060", "Central Station", "Stand B", "Sydney", C["CENB"], "10101100", dep="07:52")
    bd = street_stop("202255", "Campbell Pde at Hall St", "Bondi Beach", (-33.891300, 151.276800), arr="08:21")
    ride2 = ride_leg(bo, bd, bus("333", "City Circular Quay to North Bondi", "North Bondi", "2647901"),
                     [seq_entry(bo, dep="07:52"), seq_entry(street_stop("202233", "Oxford St at Bondi Rd", "Bondi Junction", (-33.890000, 151.250000)), dep="08:10", arr="08:10"), seq_entry(bd, arr="08:21")],
                     realtime=False, foot_after=300)
    # The final walk to the destination is foot-path information on the bus (AFTER, last leg).
    write("tfnsw/trip_train_bus_walks.json", trip_response(journey(walk1, ride1, walk2a, walk2b, ride2)))


def walk_only():
    a = address("Smith St", "Summer Hill", (-33.894900, 151.137700), dep="09:00")
    b = address("Lackey St", "Summer Hill", (-33.890800, 151.138600), arr="09:12")
    write("tfnsw/trip_walk_only.json", trip_response(journey(walk_leg(a, b, 720, 900))))


def cancelled():
    o = plat(STR, "2135233", "Platform 3", "STR3", "STR3", dep="09:02")
    d = plat(CEN, "2000336", "Platform 16", "CEN16", "CEN16", arr="09:14")
    leg = ride_leg(o, d, train("T9", "Sydney Trains Network T9 Northern Line", "Hornsby to Gordon via Strathfield",
                               "Gordon via Central", "121A.1450.133.8.T.8.86753009"),
                   [seq_entry(o, dep="09:02"), seq_entry(d, arr="09:14")], status=("TRIP_CANCELLED",))
    write("tfnsw/trip_cancelled.json", trip_response(journey(leg)))


def unmatched():
    # 07:58 from Strathfield: no T9 in the timetable leaves then, and the real-time id is unknown.
    o = plat(STR, "2135233", "Platform 3", "STR3", "STR3", dep="07:58")
    d = plat(CEN, "2000336", "Platform 16", "CEN16", "CEN16", arr="08:10")
    leg = ride_leg(o, d, train("T9", "Sydney Trains Network T9 Northern Line", "Hornsby to Gordon via Strathfield",
                               "Gordon via Central", "999Z.0000.000.0.X.0.00000000"),
                   [seq_entry(o, dep="07:58"), seq_entry(d, arr="08:10")], realtime=False)
    write("tfnsw/trip_unmatched.json", trip_response(journey(leg)))


def no_journeys():
    write("tfnsw/trip_no_journeys.json", {
        "version": VERSION,
        "systemMessages": [{"type": "error", "module": "BROKER", "code": -4000, "error": "no trips found"}],
        "journeys": [],
    })


def stop_finder(path, locations):
    write(path, {"version": VERSION, "locations": locations})


def sf_stop(gid, name, disassembled, coord, modes, suburb, quality, best=False, kind="stop"):
    return {
        "id": gid, "isGlobalId": True, "name": name, "disassembledName": disassembled, "coord": list(coord),
        "type": kind, "matchQuality": quality, "isBest": best, "modes": modes, "productClasses": modes,
        "parent": locality(suburb, 95301000 + (h(suburb)) % 999),
        "properties": {"stopId": "10101100"},
    }


def sf_place(pid, name, disassembled, coord, kind, suburb, quality):
    return {
        "id": pid, "isGlobalId": False, "name": name, "disassembledName": disassembled, "coord": list(coord),
        "type": kind, "matchQuality": quality, "isBest": False,
        "parent": locality(suburb, 95302000 + (h(suburb)) % 999),
    }


def stop_finder_fixture():
    stop_finder("tfnsw/stop_finder_central.json", [
        sf_stop("200060", "Central Station, Sydney", "Central Station", (-33.884080, 151.206290), [1, 2, 4, 5, 7], "Sydney", 951, best=True),
        sf_place("streetID:1500001234:0:95308100:-1:Central Ave:Manly", "Central Ave, Manly", "Central Ave", (-33.797400, 151.285600), "street", "Manly", 820),
        sf_place("poiID:52345:95325123:-1:Central Coast Stadium:Gosford", "Central Coast Stadium, Gosford", "Central Coast Stadium", (-33.428400, 151.338200), "poi", "Gosford", 700),
        sf_stop("2000338", "Central Station, Platform 18, Sydney", "Central Station, Platform 18", (-33.884000, 151.206700), [1], "Sydney", 650, kind="platform"),
        sf_place("placeID:95301099:1", "Central Tilba", "Central Tilba", (-36.312900, 150.073500), "locality", "Central Tilba", 600),
        {"id": "x1", "name": "???", "type": "unknown", "coord": [-33.0, 151.0], "matchQuality": 1},
    ])


# ---------------------------------------------------------------------------------------------
# Replay fixtures (replay/planner/): the mini-rail GTFS fixture's stations and trips, so a server
# with `tt-admin import-gtfs syd-rail testdata/gtfs/mini-rail` matches the rides. A journey with one
# change: Eddy Ave -> (walk) Central -> T1 -> North Sydney -> (walk) -> bus 202 -> (walk) Cammeray.

MR = {
    "CEN": dict(station_gid="CEN", station="Central Station", suburb="Sydney", legacy="CEN"),
    "NSY": dict(station_gid="NSY", station="North Sydney Station", suburb="North Sydney", legacy="NSY"),
}
MRC = {"CEN_P1": (-33.8831, 151.2064), "NSY_P1": (-33.8405, 151.2074), "NSY_D": (-33.8402, 151.2079),
       "THL_P1": (-33.8736, 151.2070), "WYN_P1": (-33.8658, 151.2060)}


def mini_journey(dep_train, arr_train, rt_id, walk_from, est_delay_min=0):
    def hm(base, add):
        h, m = map(int, base.split(":"))
        total = h * 60 + m + add
        return "%02d:%02d" % (total // 60, total % 60)

    start = address("Eddy Ave", "Haymarket", (-33.884300, 151.207300), dep=walk_from)
    cen = station_stop("CEN_P1", "CEN", "Central Station", "Platform 1", "Sydney", MRC["CEN_P1"], "CEN", platform_code="CEN1", arr=dep_train)
    walk1 = walk_leg(start, cen, 240, 260)
    o = station_stop("CEN_P1", "CEN", "Central Station", "Platform 1", "Sydney", MRC["CEN_P1"], "CEN", platform_code="CEN1",
                     dep=dep_train, dep_est=hm(dep_train, est_delay_min) if est_delay_min else None, occupancy="MANY_SEATS")
    d = station_stop("NSY_P1", "NSY", "North Sydney Station", "Platform 1", "North Sydney", MRC["NSY_P1"], "NSY", platform_code="NSY1",
                     arr=arr_train, arr_est=hm(arr_train, est_delay_min) if est_delay_min else None)
    stops = [seq_entry(o, dep=dep_train),
             seq_entry(station_stop("THL_P1", "THL", "Town Hall Station", "Platform 1", "Sydney", MRC["THL_P1"], "THL"), arr=hm(dep_train, 3), dep=hm(dep_train, 3)),
             seq_entry(station_stop("WYN_P1", "WYN", "Wynyard Station", "Platform 1", "Sydney", MRC["WYN_P1"], "WYN"), arr=hm(dep_train, 6), dep=hm(dep_train, 6)),
             seq_entry(d, arr=arr_train)]
    ride1 = ride_leg(o, d, train("T1", "Sydney Trains Network T1 North Shore & Western Line", "City to Berowra via Gordon",
                                 "Chatswood", rt_id), stops, realtime=bool(est_delay_min),
                     foot_after=180, interchange=[list(MRC["NSY_P1"]), list(MRC["NSY_D"])])
    bus_dep = hm(arr_train, 7)
    bus_arr = hm(arr_train, 15)
    bo = station_stop("206041", "206010", "North Sydney Station", "Stand D", "North Sydney", MRC["NSY_D"], "10101326", dep=bus_dep)
    bd = street_stop("206271", "Miller St at Amherst St", "Cammeray", (-33.8238, 151.2102), arr=bus_arr)
    ride2 = ride_leg(bo, bd, bus("202", "North Sydney to Northbridge", "Northbridge", "2649" + rt_id[-3:].replace(".", "0")),
                     [seq_entry(bo, dep=bus_dep), seq_entry(street_stop("206230", "Miller St at Falcon St", "North Sydney", (-33.8330, 151.2090)), arr=hm(arr_train, 11), dep=hm(arr_train, 11)), seq_entry(bd, arr=bus_arr)],
                     realtime=False)
    end_walk = walk_leg(street_stop("206271", "Miller St at Amherst St", "Cammeray", (-33.8238, 151.2102), dep=bus_arr),
                        address("Miller St", "Cammeray", (-33.8220, 151.2110), arr=hm(bus_arr, 3)), 180, 210)
    return journey(walk1, ride1, ride2, end_walk)


def replay():
    write("replay/planner/trip.json", trip_response(
        # T1.N.0700 by its real-time trip id (rule 1), running 2 minutes late.
        mini_journey("07:00", "07:11", "T1.N.0700", "06:56", est_delay_min=2),
        # T1.N.0730 with a real-time id that is not in the timetable: matched by line, time and stops (rule 2).
        mini_journey("07:30", "07:41", "145F.1361.120.48.A.8.79999925", "07:26"),
    ))
    stop_finder("replay/planner/stop_finder.json", [
        sf_stop("CEN", "Central Station, Sydney", "Central Station", (-33.8832, 151.2063), [1, 2], "Sydney", 950, best=True),
        sf_stop("CHA", "Chatswood Station, Chatswood", "Chatswood Station", (-33.7969, 151.1803), [1, 2], "Chatswood", 940),
        sf_stop("NSY", "North Sydney Station, North Sydney", "North Sydney Station", (-33.8406, 151.2073), [1, 5], "North Sydney", 930),
        sf_stop("THL", "Town Hall Station, Sydney", "Town Hall Station", (-33.8737, 151.2069), [1], "Sydney", 920),
        sf_stop("WYN", "Wynyard Station, York St, Sydney", "Wynyard Station", (-33.8659, 151.2059), [1], "Sydney", 910),
        sf_stop("MPL", "Martin Place Station, Sydney", "Martin Place Station", (-33.8677, 151.2101), [2], "Sydney", 900),
        sf_place("streetID:1500000001:0:95301001:-1:Eddy Ave:Haymarket", "Eddy Ave, Haymarket", "Eddy Ave", (-33.884300, 151.207300), "street", "Haymarket", 800),
        sf_place("streetID:1500000002:0:95308100:-1:Miller St:Cammeray", "Miller St, Cammeray", "Miller St", (-33.8220, 151.2110), "street", "Cammeray", 790),
        sf_place("poiID:50001:95301001:-1:Central Park Mall:Chippendale", "Central Park Mall, Chippendale", "Central Park Mall", (-33.884400, 151.200600), "poi", "Chippendale", 700),
    ])


if __name__ == "__main__":
    direct_train()
    train_change()
    train_bus_walks()
    walk_only()
    cancelled()
    unmatched()
    no_journeys()
    stop_finder_fixture()
    replay()
