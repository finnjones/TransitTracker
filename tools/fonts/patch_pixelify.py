#!/usr/bin/env python3
"""Patches the bundled Pixelify Sans files for legibility. Idempotent; edits the files in place.

Pixelify Sans (SIL OFL 1.1) is the app's face for names and rows. Two things in the original
make station names hard to read at phone sizes:

1. Capital B has four rounded corners and a middle bar that does not reach the stem, so it
   reads as a G or an 8: "Berowra" looks like "Gerowra". It is redrawn here with a full-height
   stem (as the font's own D and P have) and a bar that joins the stem, giving two counters.
2. Capital Z is a mirrored S and reads as a 2: "Zetland" looks like "2etland". It is redrawn
   with two full bars and a stepped diagonal.
3. The round brackets bulge to a point in the middle and read as curly braces:
   "(CC BY 4.0)" looks like "{CC BY 4.0}". They are redrawn as a straight stroke with turned
   ends, one cell narrower.
4. The standard ligatures join "fi", "fl" and "ff" into shapes that read as other letters:
   "Strathfield" looks like "StrathAeld". The `liga` (and `dlig`) features are switched off for
   every script, so the letters are always drawn separately on every platform.

The OFL allows modified versions under the same licence. The files are renamed inside
("TT Pixelify") so the modified font is not passed off as the original; the copyright notice
stays. The licence text is in app/ui/src/commonMain/composeResources/files/licenses.

Usage:  python3 tools/fonts/patch_pixelify.py            (needs fontTools)
"""

from pathlib import Path

from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont

FONT_DIR = Path(__file__).resolve().parents[2] / "app/ui/src/commonMain/composeResources/font"
FILES = ["pixelify_sans_400.ttf", "pixelify_sans_600.ttf", "pixelify_sans_700.ttf"]
FAMILY = "TT Pixelify"
NOTE = "Modified from Pixelify Sans 1.000 for TransitTracker: B, Z and round brackets redrawn, standard ligatures off."


def contours(font, name):
    glyph = font["glyf"][name]
    coords, ends, _ = glyph.getCoordinates(font["glyf"])
    out, start = [], 0
    for end in ends:
        out.append([tuple(p) for p in coords[start:end + 1]])
        start = end + 1
    return out


def replace_glyph(font, name, shape, left):
    pen = TTGlyphPen(None)
    for contour in shape:
        pen.moveTo(contour[0])
        for point in contour[1:]:
            pen.lineTo(point)
        pen.closePath()
    advance, _ = font["hmtx"][name]
    font["glyf"][name] = pen.glyph()
    font["hmtx"][name] = (advance, left)


def redraw_z(font):
    """Two full bars and a diagonal of five steps, each two cells wide. Returns False when already done."""
    name = font.getBestCmap()[ord("Z")]
    shape = contours(font, name)
    if len(shape) == 1 and len(shape[0]) == 22:
        return False
    if len(shape) != 1 or len(shape[0]) != 24:
        raise SystemExit(f"unexpected Z outline in {font.reader.file.name}: {shape}")
    xs = sorted({x for x, _ in shape[0]})
    ys = sorted({y for _, y in shape[0]})
    left, right, bottom, top = xs[0], xs[-1], ys[0], ys[-1]
    bar_bottom, bar_top = ys[2], ys[-3]  # inner edges of the bottom and top bars
    x = [round(left + k * (right - left) / 5) for k in range(6)]
    y = [round(bar_bottom + k * (bar_top - bar_bottom) / 5) for k in range(6)]
    outline = [
        (left, bottom), (left, y[1]), (x[1], y[1]), (x[1], y[2]), (x[2], y[2]), (x[2], y[3]),
        (x[3], y[3]), (x[3], y[4]), (x[4], y[4]), (x[4], bar_top), (left, bar_top), (left, top),
        (right, top), (right, y[3]), (x[4], y[3]), (x[4], y[2]), (x[3], y[2]), (x[3], y[1]),
        (x[2], y[1]), (x[2], bar_bottom), (right, bar_bottom), (right, bottom),
    ]
    replace_glyph(font, name, [outline], left)
    return True


def redraw_brackets(font):
    """A straight stroke with turned ends instead of a pointed bulge. Returns False when already done."""
    cmap = font.getBestCmap()
    changed = False
    for char in "()":
        name = cmap[ord(char)]
        shape = contours(font, name)
        if len(shape) == 1 and len(shape[0]) == 12:
            continue
        if len(shape) != 1 or len(shape[0]) != 20:
            raise SystemExit(f"unexpected {char} outline in {font.reader.file.name}: {shape}")
        p = shape[0]
        advance, bearing = font["hmtx"][name]
        if char == "(":
            # Move the turned ends one cell left, onto the old middle cell; the stem is the old bulge.
            outline = [
                (p[2][0], p[0][1]), (p[2][0], p[1][1]), (p[4][0], p[1][1]), (p[4][0], p[8][1]),
                (p[7][0], p[8][1]), (p[7][0], p[9][1]), (p[12][0], p[9][1]), (p[12][0], p[11][1]),
                (p[14][0], p[11][1]), (p[14][0], p[17][1]), (p[16][0], p[17][1]), (p[16][0], p[0][1]),
            ]
            narrower = p[0][0] - p[2][0]
        else:
            # The turned ends stay; the stem is the old middle cell.
            outline = [
                (p[0][0], p[0][1]), (p[0][0], p[1][1]), (p[2][0], p[1][1]), (p[2][0], p[8][1]),
                (p[8][0], p[8][1]), (p[8][0], p[9][1]), (p[10][0], p[9][1]), (p[10][0], p[11][1]),
                (p[12][0], p[11][1]), (p[12][0], p[17][1]), (p[18][0], p[17][1]), (p[18][0], p[19][1]),
            ]
            narrower = p[14][0] - p[12][0]
        replace_glyph(font, name, [outline], bearing)
        font["hmtx"][name] = (advance - narrower, bearing)
        changed = True
    return changed


def redraw_b(font):
    """Full-height stem and a bar that reaches it. Returns False when already done."""
    name = font.getBestCmap()[ord("B")]
    shape = contours(font, name)
    if len(shape) != 2:
        return False
    outer, inner = shape
    if len(outer) != 16 or len(inner) != 8:
        raise SystemExit(f"unexpected B outline in {font.reader.file.name}: {shape}")
    # Outer: the first six points walk up the left side through its two cut corners.
    (_, bottom), _, (left, _), _, _, (_, top) = outer[:6]
    new_outer = [(left, bottom), (left, top)] + outer[6:]
    # Inner: one C-shaped hole; split it into the two counters either side of the bar.
    (x0, y0), (x1, _), (_, bar_bottom), _, (_, bar_top), _, (_, y1), _ = inner
    lower = [(x0, y0), (x1, y0), (x1, bar_bottom), (x0, bar_bottom)]
    upper = [(x0, bar_top), (x1, bar_top), (x1, y1), (x0, y1)]
    replace_glyph(font, name, [new_outer, lower, upper], left)
    return True


def ligatures_off(font):
    """Drops `liga` and `dlig` from every script and language. Returns False when already done."""
    gsub = font["GSUB"].table
    drop = {i for i, rec in enumerate(gsub.FeatureList.FeatureRecord) if rec.FeatureTag in ("liga", "dlig")}
    changed = False
    for script in gsub.ScriptList.ScriptRecord:
        systems = [script.Script.DefaultLangSys] + [rec.LangSys for rec in script.Script.LangSysRecord]
        for system in systems:
            if system is None:
                continue
            kept = [i for i in system.FeatureIndex if i not in drop]
            if len(kept) != len(system.FeatureIndex):
                system.FeatureIndex = kept
                system.FeatureCount = len(kept)
                changed = True
    return changed


def rename(font):
    names = font["name"]
    style = names.getDebugName(2) or "Regular"
    weight = font["OS/2"].usWeightClass
    changed = names.getDebugName(1) != FAMILY or names.getDebugName(10) != NOTE
    for record in list(names.names):
        if record.nameID in (16, 17):
            names.removeNames(nameID=record.nameID)
    postscript = f"{FAMILY.replace(' ', '')}-{weight}"
    for name_id, text in ((1, FAMILY), (4, f"{FAMILY} {weight}"), (6, postscript), (3, f"1.000;TT;{postscript}"), (10, NOTE)):
        names.setName(text, name_id, 3, 1, 0x409)
        names.setName(text, name_id, 1, 0, 0)
    names.setName(style, 2, 3, 1, 0x409)
    return changed


def main():
    for file in FILES:
        path = FONT_DIR / file
        font = TTFont(path)
        done = [redraw_b(font), redraw_z(font), redraw_brackets(font), ligatures_off(font), rename(font)]
        if any(done):
            font.save(path)
        print(f"{file}: B={done[0]} Z={done[1]} brackets={done[2]} ligatures off={done[3]} renamed={done[4]}")


if __name__ == "__main__":
    main()
