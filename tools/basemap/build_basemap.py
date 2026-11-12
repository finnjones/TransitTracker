#!/usr/bin/env python3
"""Builds the base map the app draws under its lines: water, roads and railways for Sydney.

The app's map is its own pixel map (frontend plan, decision F5), so there is no tile server and
no map key. This script turns open data into one small text file that the app bundles:

  Water     OpenStreetMap: the sea from the coastline (osmdata.openstreetmap.de,
            water-polygons-split-4326.zip: the ocean and Botany Bay), plus every water area of
            2 ha or more from an OSM extract (natural=water, waterway=riverbank,
            landuse=reservoir): Port Jackson and the harbours, the rivers, the reservoirs. The
            coastline closes across the harbour heads, so the harbour only comes from the areas.
            Multipolygons are assembled forgivingly (their outer ways polygonised), because the
            big ones (Port Jackson: 520 ways) fail strict assembly in a clipped extract.
  Roads     OpenStreetMap highways, in three ranks: main (motorway, trunk, primary), secondary
            (secondary, tertiary) and local (residential, unclassified).
  Railways  OpenStreetMap railways (rail, subway, light rail) that are running lines: no
            sidings, yards or crossovers (anything with a service tag).

Licence: OpenStreetMap data is ODbL, (c) OpenStreetMap contributors. The map shows the credit
and the licence is bundled with the app (Settings > Licences).

Output (one feature per line, a kind letter then a Google encoded polyline, precision 5):
  W  a water polygon's outer ring          H  a hole in the water polygon before it (an island)
  M  a main road                           S  a secondary road
  L  a local street                        R  a railway
The first line is a header: "TTBASEMAP 1 <south> <west> <north> <east>".

Usage:
  python3 tools/basemap/build_basemap.py \\
      --osm greater_metropolitan_sydney-latest.osm.pbf \\   (download.openstreetmap.fr)
      --coast water-polygons-split-4326.zip \\
      --out app/ui/src/commonMain/composeResources/files/basemap/sydney.txt
Needs pyshp, shapely and osmium (pip install pyshp shapely osmium).
"""
import argparse
import math
import zipfile
from collections import defaultdict

# Greater Sydney, as far as the syd-rail and syd-bus packs reach for everyday trips.
BBOX = (-34.20, 150.55, -33.40, 151.40)  # south, west, north, east

WATER_TOLERANCE_M = 8.0  # simplification of the water's edge
MIN_WATER_M2 = 20_000.0  # smaller water (ponds, pools) is left out
ROAD_TOLERANCE_M = {"M": 5.0, "S": 5.0, "L": 8.0}
ROAD_RANKS = {
    "motorway": "M", "trunk": "M", "primary": "M",
    "secondary": "S", "tertiary": "S",
    "residential": "L", "unclassified": "L",
}
WATER_TAGS = (("natural", "water"), ("waterway", "riverbank"), ("landuse", "reservoir"))

RAILWAYS = ("rail", "subway", "light_rail")
RAIL_TOLERANCE_M = 5.0

ORIGIN_LAT = -33.8688
KX = 111_320.0 * math.cos(math.radians(ORIGIN_LAT))
KY = 111_320.0


def project(lat, lon):
    return (lon * KX, lat * KY)


def unproject(x, y):
    return (y / KY, x / KX)


def inside(lat, lon, pad=0.0):
    s, w, n, e = BBOX
    return s - pad <= lat <= n + pad and w - pad <= lon <= e + pad


# ---------------------------------------------------------------- polyline encoding

def encode(points):
    """Google encoded polyline of (lat, lon) points, precision 5."""
    out = []
    plat = plon = 0
    for lat, lon in points:
        ilat, ilon = round(lat * 1e5), round(lon * 1e5)
        for v in (ilat - plat, ilon - plon):
            v = ~(v << 1) if v < 0 else v << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1F)) + 63))
                v >>= 5
            out.append(chr(v + 63))
        plat, plon = ilat, ilon
    return "".join(out)


def simplify(points, tol):
    """Douglas-Peucker on projected (x, y) points; iterative so long lines do not recurse deep."""
    if len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        length = math.hypot(dx, dy)
        worst, at = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            d = math.hypot(px - ax, py - ay) if length == 0 else abs(dy * px - dx * py + bx * ay - by * ax) / length
            if d > worst:
                worst, at = d, i
        if worst > tol:
            keep[at] = True
            stack += [(a, at), (at, b)]
    return [p for p, k in zip(points, keep) if k]


def to_metres(geom):
    from shapely.ops import transform
    return transform(lambda x, y: (x * KX, y * KY), geom)


def area_box():
    from shapely.geometry import box
    s, w, n, e = BBOX
    return box(w, s, e, n)


# ---------------------------------------------------------------- water

def read_sea(path):
    """The sea from OSM's coastline water polygons, clipped to the area (lon/lat)."""
    import shapefile
    from shapely.geometry import shape

    s, w, n, e = BBOX
    area = area_box()
    z = zipfile.ZipFile(path)
    base = next(nm[:-4] for nm in z.namelist() if nm.endswith(".shp"))
    reader = shapefile.Reader(shp=z.open(base + ".shp"), shx=z.open(base + ".shx"), dbf=z.open(base + ".dbf"))
    out = []
    for sr in reader.iterShapes():
        x0, y0, x1, y1 = sr.bbox
        if x1 < w or x0 > e or y1 < s or y0 > n:
            continue
        g = shape(sr.__geo_interface__).intersection(area)
        if not g.is_empty:
            out.append(g)
    print(f"  sea: {len(out)} pieces")
    return out


def read_lakes_and_rivers(path):
    """OSM water areas (lon/lat): closed ways as they are, multipolygons assembled forgivingly."""
    import osmium
    from shapely.geometry import LineString, Polygon
    from shapely.ops import polygonize, unary_union

    def watery(tags):
        return any(tags.get(k) == v for k, v in WATER_TAGS)

    relations = {}
    for r in osmium.FileProcessor(path).with_filter(osmium.filter.EntityFilter(osmium.osm.RELATION)):
        if r.tags.get("type") == "multipolygon" and watery(r.tags):
            relations[r.id] = [(m.ref, m.role) for m in r.members if m.type == "w"]
    member_ids = {ref for ms in relations.values() for ref, _ in ms}
    coords = {}
    closed = []
    for wy in osmium.FileProcessor(path).with_locations().with_filter(osmium.filter.EntityFilter(osmium.osm.WAY)):
        is_member = wy.id in member_ids
        own = watery(wy.tags) and wy.is_closed()
        if not (is_member or own):
            continue
        pts = [(nd.lon, nd.lat) for nd in wy.nodes if nd.location.valid()]
        if len(pts) < 2:
            continue
        if is_member:
            coords[wy.id] = pts
        if own and len(pts) >= 4:
            closed.append(Polygon(pts))
    assembled = []
    for ms in relations.values():
        outer = [LineString(coords[ref]) for ref, role in ms if role != "inner" and ref in coords]
        inner = [LineString(coords[ref]) for ref, role in ms if role == "inner" and ref in coords]
        if not outer:
            continue
        g = unary_union(list(polygonize(unary_union(outer))))
        if inner:
            g = g.difference(unary_union(list(polygonize(unary_union(inner)))))
        assembled.append(g)
    area = area_box()
    out = []
    for g in closed + assembled:
        g = g.buffer(0) if not g.is_valid else g
        if g.is_empty or to_metres(g).area < MIN_WATER_M2 or not g.intersects(area):
            continue
        out.append(g.intersection(area))
    print(f"  lakes and rivers: {len(out)} areas ({len(closed)} closed ways, {len(assembled)} multipolygons)")
    return out


def water_polygons(sea, lakes):
    """Everything wet, merged and simplified: yields (outer ring, [holes]) in metres."""
    from shapely.ops import unary_union

    wet = to_metres(unary_union(sea + lakes)).simplify(WATER_TOLERANCE_M)
    for poly in getattr(wet, "geoms", [wet]):
        if poly.geom_type != "Polygon" or poly.area < MIN_WATER_M2:
            continue
        holes = [list(r.coords) for r in poly.interiors if len(r.coords) > 3]
        yield list(poly.exterior.coords), holes


# ---------------------------------------------------------------- roads

def read_roads(path):
    """OSM highways in the area by rank: yields (rank letter, [(x, y)] in metres)."""
    import osmium

    counts = defaultdict(int)
    for wy in osmium.FileProcessor(path).with_locations().with_filter(osmium.filter.EntityFilter(osmium.osm.WAY)).with_filter(osmium.filter.KeyFilter("highway")):
        rank = ROAD_RANKS.get(wy.tags.get("highway"))
        if rank is None or wy.tags.get("area") == "yes":
            continue
        pts = [(nd.lat, nd.lon) for nd in wy.nodes if nd.location.valid()]
        if len(pts) < 2 or not any(inside(la, lo) for la, lo in pts):
            continue
        counts[rank] += 1
        yield rank, simplify([project(la, lo) for la, lo in pts], ROAD_TOLERANCE_M[rank])
    print("  roads: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))


# ---------------------------------------------------------------- railways

def read_railways(path):
    """OSM running lines (rail, subway, light rail; no sidings or yards) in metres."""
    import osmium

    count = 0
    for wy in osmium.FileProcessor(path).with_locations().with_filter(osmium.filter.EntityFilter(osmium.osm.WAY)).with_filter(osmium.filter.KeyFilter("railway")):
        if wy.tags.get("railway") not in RAILWAYS or "service" in wy.tags:
            continue
        pts = [(nd.lat, nd.lon) for nd in wy.nodes if nd.location.valid()]
        if len(pts) < 2 or not any(inside(la, lo) for la, lo in pts):
            continue
        count += 1
        yield simplify([project(la, lo) for la, lo in pts], RAIL_TOLERANCE_M)
    print(f"  railways: {count}")


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--osm", required=True, help="an OSM extract (.osm.pbf) covering the area")
    ap.add_argument("--coast", required=True, help="water-polygons-split-4326.zip")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    lines = ["TTBASEMAP 1 %.2f %.2f %.2f %.2f" % BBOX]
    stats = defaultdict(int)

    def emit(kind, xy):
        stats[kind] += 1
        lines.append(kind + encode([unproject(x, y) for x, y in xy]))

    print("water")
    for outer, holes in water_polygons(read_sea(args.coast), read_lakes_and_rivers(args.osm)):
        emit("W", outer)
        for h in holes:
            emit("H", h)

    print("roads")
    # Local streets first, main roads last: later lines draw over earlier ones.
    roads = defaultdict(list)
    for rank, xy in read_roads(args.osm):
        roads[rank].append(xy)
    for rank in ("L", "S", "M"):
        for xy in roads[rank]:
            emit(rank, xy)

    print("railways")
    for xy in read_railways(args.osm):
        emit("R", xy)

    text = "\n".join(lines) + "\n"
    with open(args.out, "w") as f:
        f.write(text)
    print("  " + ", ".join(f"{k} {v}" for k, v in sorted(stats.items())))
    print(f"wrote {args.out}: {len(lines) - 1} features, {len(text) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
