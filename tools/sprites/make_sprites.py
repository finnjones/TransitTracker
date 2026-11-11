#!/usr/bin/env python3
"""Draws TransitTracker's vehicle sprites.

Every vehicle is drawn on a small pixel grid, side on and facing right, in four
frames (wheels turn, speed lines trail, road vehicles bounce, the ferry bobs).
The livery colour is a parameter, because line colours come from the timetable.

Outputs, per vehicle:
  <name>.png            sprite sheet at 1x, frames side by side, transparent (what the app loads)
  preview/<name>.gif    looping preview at 4x on the app's panel colour (for documents)

With --mockups DIR it also writes transparent 2x and 4x GIFs, and 4x PNGs of the
stopped frames, for design mockups.

App sheets (what :app:ui loads; the stripe is tinted with the line colour at run time):
  app/<name>.png          base sheet: the vehicle with its livery pixels left transparent
  app/<name>-livery.png   livery mask: white where the line colour goes, transparent elsewhere
  app/<name>-outline.png  1-dot outline around the sprite, for the Day theme (drawn in ink)
Every app frame has 1 transparent dot of padding on each side (room for the outline).
Vehicle sheets have five frames: four moving frames, then one "at a stop" frame (doors open
and lit). The walker sheet has nine: walk 0-3, stand 4, run 5-8. With --app DIR the app
sheets are also written to DIR under resource names (sprite_<name>[_livery|_outline].png);
pass app/ui/src/commonMain/composeResources/drawable to refresh the app.

Usage:  python3 tools/sprites/make_sprites.py [out_dir] [--mockups DIR] [--app DIR]   (needs Pillow)
"""
import os
import sys

from PIL import Image

BODY = "#F3ECD8"      # cream body
SHADE = "#C9C2AE"     # roof / lower body
GLASS = "#2B2B28"     # windows
LIT = "#FFD98A"       # open doors, lit interior
UNDER = "#3D3D38"     # underframe, bumpers
WHEEL = "#A3A092"
SPOKE = "#55544D"
HEAD = "#FFB020"      # headlight, destination sign
TAIL = "#FF5A5F"
DOOR = "#8E8B7E"
WATER = "#6CB6FF"
FOAM = "#F3ECD8"
TRAIL = "#A3A092"     # speed lines

FRAMES = 4
FRAME_MS = 120


class Grid:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[None] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def clear(self, *points):
        for x, y in points:
            self.set(x, y, None)

    def blit(self, other, ox, oy):
        for y in range(other.h):
            for x in range(other.w):
                if other.px[y][x] is not None:
                    self.set(ox + x, oy + y, other.px[y][x])

    def mirrored(self):
        g = Grid(self.w, self.h)
        for y in range(self.h):
            g.px[y] = list(reversed(self.px[y]))
        return g

    def rotated_cw(self):
        g = Grid(self.h, self.w)
        for y in range(self.h):
            for x in range(self.w):
                g.px[x][self.h - 1 - y] = self.px[y][x]
        return g


def rail_wheels(g, xs, y, frame):
    """Small 2x2 rail wheels; one dark pixel moves so they read as turning."""
    for x in xs:
        g.rect(x, y, x + 1, y + 1, WHEEL)
        dx, dy = [(0, 0), (1, 0), (1, 1), (0, 1)][frame % 4]
        g.set(x + dx, y + dy, SPOKE)


def road_wheel(g, x, y, frame):
    """4x4 tyre with a hub pixel that goes round."""
    g.rect(x + 1, y, x + 2, y + 3, WHEEL)
    g.rect(x, y + 1, x + 3, y + 2, WHEEL)
    dx, dy = [(1, 1), (2, 1), (2, 2), (1, 2)][frame % 4]
    g.set(x + dx, y + dy, SPOKE)


def speed_lines(g, frame, rows):
    """Short dashes in the 4 px margin behind the vehicle."""
    if frame == 0:
        g.rect(0, rows[0], 2, rows[0], TRAIL)
    elif frame == 1:
        g.rect(1, rows[1], 3, rows[1], TRAIL)
    elif frame == 2:
        g.rect(0, rows[2], 1, rows[2], TRAIL)


# ---------------------------------------------------------------- rail cars

def train_car(livery, frame, nose=True, tail=True, doors_open=False):
    """Double-deck suburban car, 26 x 14. nose/tail: shaped, lit ends."""
    g = Grid(26, 14)
    g.rect(2, 0, 23, 0, SHADE)
    g.rect(0, 1, 25, 10, BODY)
    g.rect(0, 6, 25, 6, livery)
    g.rect(0, 10, 25, 10, SHADE)
    for x0 in (2, 6, 10, 14):
        g.rect(x0, 2, x0 + 2, 3, GLASS)
    g.rect(18, 2, 19, 3, GLASS)
    for x0 in (2, 6, 14):
        g.rect(x0, 7, x0 + 2, 8, GLASS)
    g.rect(18, 7, 19, 8, GLASS)
    g.rect(10, 7, 12, 9, LIT if doors_open else DOOR)
    if nose:
        g.rect(21, 3, 23, 5, GLASS)
        g.clear((24, 0), (23, 0), (25, 1), (24, 1), (25, 2))
        g.set(25, 8, HEAD)
    else:
        g.rect(22, 2, 23, 3, GLASS)
        g.rect(22, 7, 23, 8, GLASS)
    if tail:
        g.clear((0, 1))
        g.set(0, 8, TAIL)
    g.rect(1, 11, 24, 11, UNDER)
    rail_wheels(g, (3, 6, 18, 21), 12, frame)
    return g


def metro_car(livery, frame, nose=True, tail=True, doors_open=False):
    """Single-deck metro car with a raked nose, 26 x 14."""
    g = Grid(26, 14)
    g.rect(1, 1, 22, 1, SHADE)
    g.rect(0, 2, 25, 10, BODY)
    g.rect(0, 3, 25, 3, livery)
    g.rect(0, 8, 25, 8, livery)
    g.rect(0, 10, 25, 10, SHADE)
    g.rect(1, 4, 19, 6, GLASS)
    for x in (5, 9, 12, 16):
        g.rect(x, 4, x, 6, BODY)
    for x0 in (6, 13):
        g.rect(x0, 7, x0 + 2, 9, LIT if doors_open else DOOR)
        if doors_open:
            g.rect(x0, 4, x0 + 2, 6, LIT)
    if nose:
        g.rect(21, 4, 23, 6, GLASS)
        g.clear((22, 1), (23, 2), (24, 2), (25, 2), (24, 3), (25, 3), (25, 4))
        g.set(25, 9, HEAD)
    else:
        g.rect(21, 4, 24, 6, GLASS)
    if tail:
        g.clear((0, 2))
        g.set(0, 9, TAIL)
    g.rect(1, 11, 24, 11, UNDER)
    rail_wheels(g, (3, 6, 18, 21), 12, frame)
    return g


def tram(livery, frame, doors_open=False):
    """Low-floor, two-section light rail vehicle with a pantograph, 30 x 14."""
    g = Grid(30, 14)
    for x, y in ((7, 0), (8, 0), (6, 1), (9, 1), (7, 2), (8, 2)):
        g.set(x, y, SPOKE)
    g.rect(1, 3, 28, 3, SHADE)
    g.rect(0, 4, 29, 10, BODY)
    g.rect(0, 8, 29, 9, livery)
    g.rect(0, 10, 29, 10, SHADE)
    g.clear((0, 4), (29, 4))
    g.rect(1, 5, 4, 7, GLASS)
    g.rect(10, 5, 12, 7, GLASS)
    g.rect(17, 5, 19, 7, GLASS)
    g.rect(25, 5, 28, 7, GLASS)
    for x0 in (6, 21):
        g.rect(x0, 5, x0 + 2, 10, LIT if doors_open else DOOR)
        if not doors_open:
            g.rect(x0, 5, x0 + 2, 6, GLASS)
    g.rect(14, 4, 15, 10, UNDER)
    g.set(14, 6, SPOKE)
    g.set(15, 8, SPOKE)
    g.set(29, 9, HEAD)
    g.set(0, 9, TAIL)
    rail_wheels(g, (3, 10, 18, 25), 11, frame)
    return g


# ---------------------------------------------------------------- road, water

def bus(livery, frame, doors_open=False):
    """City bus, 24 x 13 including one row of bounce."""
    g = Grid(24, 13)
    b = Grid(24, 10)
    b.rect(1, 0, 22, 0, SHADE)
    b.rect(0, 1, 23, 9, BODY)
    b.rect(0, 6, 23, 8, livery)
    b.clear((23, 1))
    for x0 in (1, 5, 9, 13):
        b.rect(x0, 2, x0 + 2, 4, GLASS)
    b.rect(17, 2, 18, 8, LIT if doors_open else DOOR)
    if not doors_open:
        b.rect(17, 2, 18, 4, GLASS)
    b.rect(20, 2, 22, 5, GLASS)
    b.rect(20, 1, 22, 1, HEAD)
    b.rect(0, 9, 23, 9, UNDER)
    b.set(23, 7, HEAD)
    b.set(0, 7, TAIL)
    bounce = 0 if (frame % 2 == 1 and not doors_open) else 1
    g.blit(b, 0, bounce)
    road_wheel(g, 3, 9, frame)
    road_wheel(g, 16, 9, frame)
    return g


def coach(livery, frame, doors_open=False):
    """Long-distance coach: taller, one long window band, two rear axles, 28 x 13."""
    g = Grid(28, 13)
    b = Grid(28, 10)
    b.rect(1, 0, 25, 0, SHADE)
    b.rect(0, 1, 27, 9, BODY)
    b.rect(0, 6, 27, 7, livery)
    b.clear((27, 1), (26, 1), (27, 2), (26, 0), (25, 0))
    b.rect(1, 2, 21, 4, GLASS)
    b.rect(24, 2, 26, 5, GLASS)
    b.rect(22, 3, 23, 8, LIT if doors_open else DOOR)
    b.rect(9, 8, 18, 8, SHADE)
    b.rect(0, 9, 27, 9, UNDER)
    b.set(27, 8, HEAD)
    b.set(0, 7, TAIL)
    bounce = 0 if (frame % 2 == 1 and not doors_open) else 1
    g.blit(b, 0, bounce)
    road_wheel(g, 2, 9, frame)
    road_wheel(g, 7, 9, frame)
    road_wheel(g, 20, 9, frame)
    return g


def ferry(livery, frame, doors_open=False):
    """Harbour ferry on moving water, 32 x 16."""
    g = Grid(32, 16)
    s = Grid(32, 13)
    s.set(21, 0, SPOKE)
    s.set(21, 1, SPOKE)
    s.set(22, 0, livery)
    s.rect(7, 2, 8, 4, livery)
    s.rect(7, 1, 8, 1, UNDER)
    s.rect(11, 2, 20, 4, BODY)
    s.rect(12, 3, 19, 3, GLASS)
    s.set(14, 3, BODY)
    s.set(17, 3, BODY)
    s.rect(4, 5, 25, 8, BODY)
    for x0 in range(5, 24, 3):
        s.rect(x0, 6, x0 + 1, 7, LIT if doors_open else GLASS)
    s.rect(26, 8, 30, 8, livery)
    s.rect(1, 9, 30, 9, livery)
    s.rect(2, 10, 29, 10, livery)
    s.rect(3, 11, 28, 11, livery)
    s.set(30, 8, HEAD)
    s.set(1, 9, TAIL)
    bob = 1 if frame in (2, 3) and not doors_open else 0
    g.blit(s, 0, bob)
    shift = frame * 2
    for x in range(32):
        k = (x + shift) % 8
        if k in (0, 1):
            g.set(x, 12, FOAM)
        elif k in (2, 3, 4):
            g.set(x, 12, WATER)
        g.set(x, 13, WATER)
        if (x + shift) % 4 != 0:
            g.set(x, 14, WATER)
    if not doors_open:
        for x in range(0, 3 - (frame % 2)):
            g.set(x, 11, FOAM)
    return g


def walker(frame):
    """A person walking, 8 x 12: shown beside walk times and when it is time to leave."""
    stride = (
        "...XX...",
        "..XXXX..",
        "...XX...",
        "..XXXX..",
        ".a.XX.b.",
        "a..XX..b",
        "...XX...",
        "...XX...",
        "..a..b..",
        "..a..b..",
        ".a....b.",
        "a......b",
    )
    passing = (
        "...XX...",
        "..XXXX..",
        "...XX...",
        "..XXXX..",
        "..XXXX..",
        "..XXXX..",
        "...XX...",
        "...XX...",
        "...aX...",
        "...aX...",
        "...aX...",
        "...aXX..",
    )
    # Limbs on the far side are shaded; they swap sides on each stride.
    if frame % 2 == 1:
        rows, colours = passing, {"X": BODY, "a": SHADE}
    elif frame == 0:
        rows, colours = stride, {"X": BODY, "a": SHADE, "b": BODY}
    else:
        rows, colours = stride, {"X": BODY, "a": BODY, "b": SHADE}
    g = Grid(8, 12)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colours:
                g.set(x, y, colours[ch])
    return g


# ---------------------------------------------------------------- composition

def with_trail(body, frame, rows, moving=True):
    """Adds a 4 px margin behind the vehicle for speed lines."""
    g = Grid(body.w + 4, body.h)
    g.blit(body, 4, 0)
    if moving:
        speed_lines(g, frame, rows)
    return g


def consist(car_fn, livery, frame, cars):
    """A multi-car train: tail car, middle cars, nose car, 1 px couplers."""
    parts = []
    for i in range(cars):
        parts.append(car_fn(livery, frame, nose=(i == cars - 1), tail=(i == 0)))
    g = Grid(sum(p.w for p in parts) + (cars - 1), parts[0].h)
    x = 0
    for i, p in enumerate(parts):
        g.blit(p, x, 0)
        x += p.w
        if i < cars - 1:
            g.rect(x, 9, x, 10, UNDER)
            x += 1
    return g


def coupled(a, b):
    """Two vehicles joined nose to tail by a 1 px coupler."""
    g = Grid(a.w + 1 + b.w, max(a.h, b.h))
    g.blit(a, 0, 0)
    g.rect(a.w, a.h - 5, a.w, a.h - 4, UNDER)
    g.blit(b, a.w + 1, 0)
    return g


def train_top(livery, frame, cars=3):
    """Top-down train heading down the screen, for the line map. 8 px wide."""
    g = Grid(8, cars * 12 + (cars - 1))
    y = 0
    for i in range(cars):
        g.rect(0, y, 7, y + 11, BODY)
        g.rect(0, y + 1, 0, y + 10, livery)
        g.rect(7, y + 1, 7, y + 10, livery)
        g.rect(3, y + 2, 4, y + 3, UNDER)
        g.rect(3, y + 8, 4, y + 9, UNDER)
        if i == 0:
            g.clear((0, y), (7, y))
            g.set(1, y, TAIL)
            g.set(6, y, TAIL)
        if i == cars - 1:
            g.clear((0, y + 11), (7, y + 11))
            g.rect(2, y + 10, 5, y + 10, GLASS)
            lamp = HEAD if frame % 2 == 0 else LIT
            g.set(1, y + 11, lamp)
            g.set(6, y + 11, lamp)
        y += 12
        if i < cars - 1:
            g.rect(3, y, 4, y, UNDER)
            y += 1
    return g


# ---------------------------------------------------------------- output

def to_image(grid, scale=1):
    img = Image.new("RGBA", (grid.w, grid.h), (0, 0, 0, 0))
    for y in range(grid.h):
        for x in range(grid.w):
            c = grid.px[y][x]
            if c is not None:
                c = c.lstrip("#")
                img.putpixel((x, y), (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), 255))
    if scale != 1:
        img = img.resize((grid.w * scale, grid.h * scale), Image.NEAREST)
    return img


def save_sheet(frames, path):
    w, h = frames[0].w, frames[0].h
    sheet = Image.new("RGBA", (w * len(frames), h), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        sheet.paste(to_image(f), (i * w, 0))
    sheet.save(path)


def save_gif(frames, path, scale, ms=FRAME_MS, background=None, pad=0):
    """Looping GIF. Transparent unless a background colour is given; pad is in art pixels."""
    colours = []
    for f in frames:
        for row in f.px:
            for c in row:
                if c is not None and c not in colours:
                    colours.append(c)
    key = (background or "#FF00FF").lstrip("#")
    palette = [int(key[0:2], 16), int(key[2:4], 16), int(key[4:6], 16)]
    for c in colours:
        c = c.lstrip("#")
        palette += [int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16)]
    palette += [0] * (768 - len(palette))
    images = []
    for f in frames:
        w, h = f.w + 2 * pad, f.h + 2 * pad
        img = Image.new("P", (w, h), 0)
        img.putpalette(palette)
        for y in range(f.h):
            for x in range(f.w):
                c = f.px[y][x]
                if c is not None:
                    img.putpixel((x + pad, y + pad), colours.index(c) + 1)
        images.append(img.resize((w * scale, h * scale), Image.NEAREST))
    extra = {} if background else {"transparency": 0, "disposal": 2}
    images[0].save(path, save_all=True, append_images=images[1:], duration=ms, loop=0,
                   optimize=False, **extra)


# name -> (builder taking frame, livery). Colours here are samples; the app tints at run time.
T1, T4, T9, M1 = "#F99D1C", "#005AA3", "#D11F2F", "#168388"
LIGHT_RAIL, BUS, FERRY, COACH = "#EE343F", "#00B5EF", "#5AB031", "#C98A1B"

MOVING = {
    "train-t1": lambda f: with_trail(train_car(T1, f), f, (4, 9, 6)),
    "train-t4": lambda f: with_trail(train_car(T4, f), f, (4, 9, 6)),
    "train-t9": lambda f: with_trail(train_car(T9, f), f, (4, 9, 6)),
    "metro-m1": lambda f: with_trail(metro_car(M1, f), f, (5, 9, 7)),
    "lightrail": lambda f: with_trail(tram(LIGHT_RAIL, f), f, (6, 9, 7)),
    "bus": lambda f: with_trail(bus(BUS, f), f, (4, 8, 6)),
    "coach": lambda f: with_trail(coach(COACH, f), f, (4, 8, 6)),
    "ferry": lambda f: ferry(FERRY, f),
    # Longer versions that cross the screen in the page transition. Bus, coach and
    # ferry cross as they are.
    "train-t9-long": lambda f: with_trail(consist(train_car, T9, f, 4), f, (4, 9, 6)),
    "metro-m1-long": lambda f: with_trail(consist(metro_car, M1, f, 4), f, (5, 9, 7)),
    "lightrail-pair": lambda f: with_trail(coupled(tram(LIGHT_RAIL, f), tram(LIGHT_RAIL, f)), f, (6, 9, 7)),
    "train-t9-top": lambda f: train_top(T9, f),
    "walker": walker,
}

STOPPED = {
    "train-t1-doors": lambda: with_trail(train_car(T1, 0, doors_open=True), 0, (0, 0, 0), moving=False),
    "bus-doors": lambda: with_trail(bus(BUS, 0, doors_open=True), 0, (0, 0, 0), moving=False),
    "ferry-doors": lambda: ferry(FERRY, 0, doors_open=True),
}


PANEL = "#181816"

# ---------------------------------------------------------------- app sheets

# Drawn in place of the line colour so the livery pixels can be separated out.
LIVERY = "#00FF01"
MASK = "#FFFFFF"
INK = "#000000"


def run_figure(frame):
    """A person running, 8 x 12: leaning forward, long stride, arms bent."""
    stride = (
        "....XX..",
        "...XXXX.",
        "....XX..",
        "..aXXX.b",
        ".a.XXXb.",
        "...XX...",
        "...XX...",
        "..XXXX..",
        ".a....b.",
        "a......b",
        "a.......",
        "........",
    )
    passing = (
        "........",
        "....XX..",
        "...XXXX.",
        "....XX..",
        "...XXXb.",
        "..aXX.b.",
        "...XX...",
        "...XXX..",
        "...a.Xb.",
        "...a.b..",
        "..a.....",
        "........",
    )
    if frame % 2 == 1:
        rows, colours = passing, {"X": BODY, "a": SHADE, "b": BODY}
    elif frame == 0:
        rows, colours = stride, {"X": BODY, "a": SHADE, "b": BODY}
    else:
        rows, colours = stride, {"X": BODY, "a": BODY, "b": SHADE}
    g = Grid(8, 12)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colours:
                g.set(x, y, colours[ch])
    return g


def standing_figure():
    """A person standing still, 8 x 12."""
    rows = (
        "...XX...",
        "..XXXX..",
        "...XX...",
        "..XXXX..",
        ".aXXXXa.",
        ".a.XX.a.",
        "...XX...",
        "...XX...",
        "...aX...",
        "...aX...",
        "...aX...",
        "..aaXX..",
    )
    g = Grid(8, 12)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "X":
                g.set(x, y, BODY)
            elif ch == "a":
                g.set(x, y, SHADE)
    return g


def other_marker(livery, frame, doors_open=False):
    """The marker for a mode the app does not know: a plain arrow in the line colour, 13 x 14."""
    rows = (
        ".......L.....",
        ".......LL....",
        ".......LLL...",
        "LLLLLLLLLLL..",
        "LLLLLLLLLLLL.",
        "LLLLLLLLLLL..",
        ".......LLL...",
        ".......LL....",
        ".......L.....",
    )
    g = Grid(13, 14)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "L":
                g.set(x, y + 3, livery)
    return g


def padded(grid, pad=1):
    g = Grid(grid.w + 2 * pad, grid.h + 2 * pad)
    g.blit(grid, pad, pad)
    return g


def split_layers(grid):
    """(base, livery mask, outline) grids for one padded frame."""
    base, mask, outline = Grid(grid.w, grid.h), Grid(grid.w, grid.h), Grid(grid.w, grid.h)
    for y in range(grid.h):
        for x in range(grid.w):
            c = grid.px[y][x]
            if c == LIVERY:
                mask.set(x, y, MASK)
            elif c is not None:
                base.set(x, y, c)
            elif any(0 <= x + dx < grid.w and 0 <= y + dy < grid.h and grid.px[y + dy][x + dx] is not None
                     for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                outline.set(x, y, INK)
    return base, mask, outline


def vehicle_frames(build, trail_rows):
    """Four moving frames and one stopped frame, all the same size."""
    if trail_rows is None:
        return [build(f, False) for f in range(FRAMES)] + [build(0, True)]
    moving = [with_trail(build(f, False), f, trail_rows) for f in range(FRAMES)]
    stopped = with_trail(build(0, True), 0, (0, 0, 0), moving=False)
    return moving + [stopped]


def long_frames(build, trail_rows):
    moving = [with_trail(build(f), f, trail_rows) for f in range(FRAMES)]
    return moving + [with_trail(build(0), 0, (0, 0, 0), moving=False)]


APP = {
    "train": lambda: vehicle_frames(lambda f, d: train_car(LIVERY, f, doors_open=d), (4, 9, 6)),
    "metro": lambda: vehicle_frames(lambda f, d: metro_car(LIVERY, f, doors_open=d), (5, 9, 7)),
    "lightrail": lambda: vehicle_frames(lambda f, d: tram(LIVERY, f, doors_open=d), (6, 9, 7)),
    "bus": lambda: vehicle_frames(lambda f, d: bus(LIVERY, f, doors_open=d), (4, 8, 6)),
    "coach": lambda: vehicle_frames(lambda f, d: coach(LIVERY, f, doors_open=d), (4, 8, 6)),
    "ferry": lambda: vehicle_frames(lambda f, d: ferry(LIVERY, f, doors_open=d), None),
    "other": lambda: vehicle_frames(lambda f, d: other_marker(LIVERY, f, d), (6, 9, 8)),
    # Transition vehicles (section 3.4). The fifth frame is the same consist standing still.
    "train_long": lambda: long_frames(lambda f: consist(train_car, LIVERY, f, 4), (4, 9, 6)),
    "metro_long": lambda: long_frames(lambda f: consist(metro_car, LIVERY, f, 4), (5, 9, 7)),
    "lightrail_pair": lambda: long_frames(lambda f: coupled(tram(LIVERY, f), tram(LIVERY, f)), (6, 9, 7)),
    # Walk 0-3, stand 4, run 5-8.
    "walker": lambda: [walker(f) for f in range(FRAMES)] + [standing_figure()] + [run_figure(f) for f in range(FRAMES)],
}


def save_grids(grids, path):
    w, h = grids[0].w, grids[0].h
    sheet = Image.new("RGBA", (w * len(grids), h), (0, 0, 0, 0))
    for i, f in enumerate(grids):
        sheet.paste(to_image(f), (i * w, 0))
    sheet.save(path, optimize=True)


def write_app_sheets(out, resources=None):
    os.makedirs(out, exist_ok=True)
    if resources:
        os.makedirs(resources, exist_ok=True)
    for name, build in APP.items():
        frames = [padded(f) for f in build()]
        assert len({(f.w, f.h) for f in frames}) == 1, name
        layers = [split_layers(f) for f in frames]
        for i, suffix in enumerate(("", "-livery", "-outline")):
            grids = [layer[i] for layer in layers]
            save_grids(grids, os.path.join(out, name.replace("_", "-") + suffix + ".png"))
            if resources:
                save_grids(grids, os.path.join(resources, "sprite_" + name + suffix.replace("-", "_") + ".png"))
        print(f"  app {name}: {len(frames)} frames of {frames[0].w} x {frames[0].h}")


def main():
    args = sys.argv[1:]
    mockups = None
    app_resources = None
    if "--app" in args:
        i = args.index("--app")
        app_resources = args[i + 1]
        del args[i:i + 2]
    if "--mockups" in args:
        i = args.index("--mockups")
        mockups = args[i + 1]
        del args[i:i + 2]
    out = args[0] if args else os.path.join("docs", "design", "sprites")
    preview = os.path.join(out, "preview")
    os.makedirs(preview, exist_ok=True)
    if mockups:
        os.makedirs(mockups, exist_ok=True)
    for name, build in MOVING.items():
        frames = [build(f) for f in range(FRAMES)]
        save_sheet(frames, os.path.join(out, name + ".png"))
        save_gif(frames, os.path.join(preview, name + ".gif"), scale=4, background=PANEL, pad=3)
        if mockups:
            save_gif(frames, os.path.join(mockups, name + "@2x.gif"), scale=2)
            save_gif(frames, os.path.join(mockups, name + "@4x.gif"), scale=4)
    for name, build in STOPPED.items():
        frame = build()
        save_sheet([frame], os.path.join(out, name + ".png"))
        save_gif([frame], os.path.join(preview, name + ".gif"), scale=4, background=PANEL, pad=3)
        if mockups:
            to_image(frame, 4).save(os.path.join(mockups, name + "@4x.png"))
    write_app_sheets(os.path.join(out, "app"), app_resources)
    print("wrote sheets to", out, "and previews to", preview)


if __name__ == "__main__":
    main()
