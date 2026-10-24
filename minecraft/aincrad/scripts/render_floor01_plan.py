"""Render the machine-readable Floor 1 manifest as a plan/elevation reference PNG."""

from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "docs" / "floor-01-layout.json"
OUTPUT = ROOT / "visuals" / "floor-01-plan-and-elevation.png"

WIDTH = 1500
HEIGHT = 1120
BACKGROUND = "#f4f6f0"
FOREGROUND = "#19231d"
MUTED = "#58645d"
BORDER = "#334239"

REGION_COLORS = {
    "south_grasslands": "#9acb65",
    "rata_plain": "#d5dc72",
    "nepenthes_forest": "#407a4b",
    "east_lake_country": "#72b9d6",
    "dallhari_divide": "#9c7656",
    "east_ruins": "#b6a58d",
    "north_mountains": "#7d8b72",
    "tolbana_valley": "#9dbb76",
    "north_labyrinth_forest": "#355c45",
}


def load_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        Path("C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf"),
        Path("C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


TITLE_FONT = load_font(28, bold=True)
HEADING_FONT = load_font(19, bold=True)
LABEL_FONT = load_font(15, bold=True)
SMALL_FONT = load_font(13)
TINY_FONT = load_font(11)


def terrain_height(x: float, z: float) -> float:
    y = 320.0
    y += math.sin(x / 480.0) * 3.0 + math.cos(z / 620.0) * 3.0
    if z < -1300:
        y += min(58.0, (-z - 1300.0) * 0.0175)
    if z < -2600:
        y += 10.0
    if 1400 < x and -2000 < z < 2600:
        y = min(y, 322.0)
    if -2360 < z < -1120:
        canyon = max(0.0, 1.0 - abs(z + 1700.0) / 640.0)
        crossing_relief = max(
            max(0.0, 1.0 - abs(x + 2560.0) / 520.0),
            max(0.0, 1.0 - abs(x - 2420.0) / 600.0),
        )
        y -= canyon * (28.0 - crossing_relief * 21.0)
    tolbana_notch = max(
        0.0,
        1.0 - math.hypot((x - 700.0) / 1300.0, (z + 3480.0) / 1040.0),
    )
    y -= tolbana_notch * 20.0
    return y


def text_box(
    draw: ImageDraw.ImageDraw,
    xy: tuple[int, int],
    text: str,
    font: ImageFont.ImageFont,
    fill: str = FOREGROUND,
    anchor: str = "la",
    background: str | None = None,
) -> None:
    if background:
        bounds = draw.textbbox(xy, text, font=font, anchor=anchor)
        draw.rounded_rectangle(
            (bounds[0] - 4, bounds[1] - 2, bounds[2] + 4, bounds[3] + 2),
            radius=3,
            fill=background,
        )
    draw.text(xy, text, font=font, fill=fill, anchor=anchor)


def main() -> None:
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)

    image = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw = ImageDraw.Draw(image, "RGBA")

    draw.text((56, 36), "Aincrad Floor 1 — canon-constrained Minecraft reconstruction", font=TITLE_FONT, fill=FOREGROUND)
    draw.text(
        (58, 75),
        "True 1:1 horizontal/vertical scale · 10,000-block diameter · north at top · derived coordinates are not official canon",
        font=SMALL_FONT,
        fill=MUTED,
    )

    plan_left = 55
    plan_top = 112
    plan_size = 770
    plan_center_x = plan_left + plan_size // 2
    plan_center_y = plan_top + plan_size // 2
    world_radius = data["world_shell"]["build_radius_blocks"]
    plan_scale = (plan_size / 2 - 12) / float(world_radius)

    def plan_point(x: float, z: float) -> tuple[int, int]:
        return (
            round(plan_center_x + x * plan_scale),
            round(plan_center_y + z * plan_scale),
        )

    radius_px = round(world_radius * plan_scale)
    draw.ellipse(
        (
            plan_center_x - radius_px,
            plan_center_y - radius_px,
            plan_center_x + radius_px,
            plan_center_y + radius_px,
        ),
        fill="#e0e7d6",
        outline=BORDER,
        width=4,
    )

    for region in data["terrain_regions"]:
        polygon = [plan_point(point[0], point[1]) for point in region["polygon"]]
        draw.polygon(
            polygon,
            fill=REGION_COLORS.get(region["id"], "#c9c5b8"),
            outline="#45534b",
            width=2,
        )

    for route in data["routes"]:
        points = [plan_point(point[0], point[1]) for point in route["points"]]
        primary = route["class"] == "primary"
        draw.line(points, fill="#594b35", width=7 if primary else 5, joint="curve")
        draw.line(points, fill="#ead8a6", width=5 if primary else 3, joint="curve")

    town = next(item for item in data["settlements"] if item["id"] == "town_of_beginnings")
    town_footprint = town["footprint"]
    town_radius = town_footprint["south_arc_radius_blocks"]
    town_center_x, town_center_z = town_footprint["arc_center"]
    town_chord_z = town_footprint["north_chord_z"]
    town_arc = [
        (town_center_x - town_radius, town_chord_z),
        (town_center_x + town_radius, town_chord_z),
    ]
    town_arc.extend(
        (
            town_center_x + math.cos(math.pi * step / 60.0) * town_radius,
            town_center_z + math.sin(math.pi * step / 60.0) * town_radius,
        )
        for step in range(61)
    )
    draw.polygon(
        [plan_point(x, z) for x, z in town_arc],
        fill="#e7d8b9",
        outline="#4c3e33",
        width=3,
    )

    settlement_labels = {
        "town_of_beginnings": ("Town of Beginnings", 0, -22, "ma"),
        "horunka": ("Horunka Village", -8, -18, "la"),
        "medai": ("Medai Village", 8, -18, "la"),
        "tolbana": ("Tolbana", 10, -20, "la"),
        "west_safe_hamlet": ("West Safe Hamlet", -6, -18, "la"),
        "east_safe_hamlet": ("East Safe Hamlet", -8, -18, "ra"),
    }
    for settlement in data["settlements"]:
        x, _, z = settlement["center"]
        px, py = plan_point(x, z)
        marker_radius = 8 if settlement["id"] == "town_of_beginnings" else 6
        draw.ellipse(
            (px - marker_radius, py - marker_radius, px + marker_radius, py + marker_radius),
            fill="#f0d178",
            outline=FOREGROUND,
            width=2,
        )
        label, ox, oy, anchor = settlement_labels.get(
            settlement["id"],
            (settlement["label"], 7, -12, "la"),
        )
        text_box(
            draw,
            (px + ox, py + oy),
            label,
            LABEL_FONT if settlement["id"] in {"town_of_beginnings", "tolbana"} else SMALL_FONT,
            anchor=anchor,
            background="#f4f6f0dd",
        )

    landmark_offsets = {
        "rata_field_boss_basin": (-10, 14),
        "north_cave": (-8, 14),
        "eastern_ruins": (8, 14),
        "floor_01_labyrinth": (10, -20),
    }
    for landmark in data["landmarks"]:
        if landmark["id"] in {
            "teleport_gate_plaza",
            "black_iron_palace",
            "hidden_dungeon_intake",
            "dallhari_west_crossing",
            "dallhari_east_crossing",
        }:
            continue
        x, _, z = landmark["position"]
        px, py = plan_point(x, z)
        size = 7
        draw.polygon(
            [(px, py - size), (px + size, py), (px, py + size), (px - size, py)],
            fill="#a15f51",
            outline=FOREGROUND,
        )
        ox, oy = landmark_offsets.get(landmark["id"], (8, 12))
        display_label = {
            "rata_field_boss_basin": "Rata Boss Basin",
            "floor_01_labyrinth": "First Floor Labyrinth",
        }.get(landmark["id"], landmark["label"])
        text_box(
            draw,
            (px + ox, py + oy),
            display_label,
            TINY_FONT,
            anchor="la",
            background="#f4f6f0dd",
        )

    for grid_value in [-4000, -2000, 0, 2000, 4000]:
        x1, _ = plan_point(grid_value, 0)
        _, y1 = plan_point(0, grid_value)
        draw.line((x1, plan_top + 5, x1, plan_top + plan_size - 5), fill="#54625944", width=1)
        draw.line((plan_left + 5, y1, plan_left + plan_size - 5, y1), fill="#54625944", width=1)

    north_x = plan_left + 48
    north_y = plan_top + 56
    draw.line((north_x, north_y + 45, north_x, north_y), fill=FOREGROUND, width=3)
    draw.polygon(
        [(north_x, north_y - 8), (north_x - 7, north_y + 6), (north_x + 7, north_y + 6)],
        fill=FOREGROUND,
    )
    draw.text((north_x, north_y - 14), "N", font=LABEL_FONT, fill=FOREGROUND, anchor="ms")

    info_left = 865
    draw.text((info_left, 126), "Locked geography", font=HEADING_FONT, fill=FOREGROUND)
    locked_lines = [
        "• 10 km circular First Floor",
        "• Town at southern tip",
        "• Horunka + deep forest northwest",
        "• lakes east / northeast",
        "• valleys, ruins, mountains north",
        "• Tolbana nearest safe town",
        "• Labyrinth on far northern edge",
    ]
    y = 164
    for line in locked_lines:
        draw.text((info_left, y), line, font=SMALL_FONT, fill=FOREGROUND)
        y += 26

    draw.text((info_left, y + 16), "Recommended world envelope", font=HEADING_FONT, fill=FOREGROUND)
    envelope_lines = [
        "Radius: 5,000 blocks",
        "Grassland datum: Y 320",
        "Build range: Y -64..1967",
        "Dallhari floor: Y 294–308",
        "North ridges: Y 345–408",
        "Labyrinth: 300 × 300 × 100 exterior",
        "Town of Beginnings: ~1,000 blocks",
        "Tolbana: 200 strict / 220 gameplay",
    ]
    y += 58
    for line in envelope_lines:
        draw.text((info_left, y), line, font=SMALL_FONT, fill=MUTED)
        y += 25

    draw.text((info_left, y + 18), "Evidence", font=HEADING_FONT, fill=FOREGROUND)
    evidence_lines = [
        "C1/C2  canon fact or relationship",
        "A1       licensed adaptation reference",
        "D1       derived reconstruction geometry",
        "O1       original playable connective content",
    ]
    y += 58
    for line in evidence_lines:
        draw.text((info_left, y), line, font=SMALL_FONT, fill=FOREGROUND)
        y += 25

    draw.text((58, 915), "South–north elevation concept", font=HEADING_FONT, fill=FOREGROUND)
    elev_left = 65
    elev_right = WIDTH - 65
    elev_top = 952
    elev_bottom = 1075
    elev_y_min = -64
    elev_y_max = 520

    def elev_point(z: float, height: float) -> tuple[int, int]:
        px = round(elev_left + ((world_radius - z) / (world_radius * 2.0)) * (elev_right - elev_left))
        py = round(elev_bottom - ((height - elev_y_min) / (elev_y_max - elev_y_min)) * (elev_bottom - elev_top))
        return px, py

    for height in [0, 160, 320, 480]:
        _, py = elev_point(0, height)
        draw.line((elev_left, py, elev_right, py), fill="#59665e55", width=1)
        draw.text((elev_left - 8, py), f"Y {height}", font=TINY_FONT, fill=MUTED, anchor="rm")

    samples = [(z, terrain_height(0, z)) for z in range(world_radius, -world_radius - 1, -20)]
    terrain_points = [elev_point(z, height) for z, height in samples]
    underside_center = data["world_shell"]["underside_center_y"]
    ground_polygon = terrain_points + [
        elev_point(-world_radius, underside_center),
        elev_point(world_radius, underside_center),
    ]
    draw.polygon(ground_polygon, fill="#7d8b7288")
    draw.line(terrain_points, fill="#405346", width=3, joint="curve")

    reference_y = elev_point(0, data["world_shell"]["second_floor_underside_reference_y"])[1]
    draw.line((elev_left, reference_y, elev_right, reference_y), fill="#53697a", width=2)
    draw.text(
        (elev_right - 6, reference_y - 6),
        "narrative Floor 2 underside reference",
        font=TINY_FONT,
        fill="#40515e",
        anchor="rs",
    )

    elevation_marks = [
        (4500, 322, "Town"),
        (420, 319, "Rata"),
        (-1700, 298, "Dallhari"),
        (-3480, 358, "Tolbana"),
        (-4700, 476, "Labyrinth top"),
    ]
    for z, height, label in elevation_marks:
        px, py = elev_point(z, height)
        marker = "#a15f51" if "Labyrinth" in label else "#d4a940"
        draw.ellipse((px - 5, py - 5, px + 5, py + 5), fill=marker, outline=FOREGROUND)
        draw.text((px + 7, py - 4), label, font=TINY_FONT, fill=FOREGROUND, anchor="ls")

    draw.text((elev_left, 1095), "South rim", font=TINY_FONT, fill=MUTED, anchor="ls")
    draw.text(((elev_left + elev_right) // 2, 1095), "Floor center", font=TINY_FONT, fill=MUTED, anchor="ms")
    draw.text((elev_right, 1095), "North rim", font=TINY_FONT, fill=MUTED, anchor="rs")

    image.save(OUTPUT, format="PNG", optimize=True)
    print(OUTPUT)


if __name__ == "__main__":
    main()
