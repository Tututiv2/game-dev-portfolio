"""Perception: HUD parsing, entity classification, island layouts, the digest, the PNG codec."""
import base64
import json
import os
import struct
import sys
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "orchestrator"))

from show import observe as ob  # noqa: E402
from show.config import SHOW  # noqa: E402
from show.mock import MockWorld  # noqa: E402

SAMPLE_STATE = Path(SHOW.paths["testpilot"]) / "verify" / "smoke_26_2" / "state.json"
SAMPLE_SHOT = Path(SHOW.paths["testpilot"]) / "verify" / "smoke_26_2" / "04_hit.png"


class TextTests(unittest.TestCase):
    def test_clean_strips_codes(self):
        self.assertEqual(ob.clean("§aYou bought §fWheat§a!"), "You bought Wheat!")
        self.assertEqual(ob.clean("&8[&7Lv1&8] &cZombie"), "[Lv1] Zombie")
        self.assertEqual(ob.clean("§x§f§f§0§0§0§0Red"), "Red")

    def test_fuzzy(self):
        self.assertEqual(ob.fuzzy_score("Your Skills", "Your Skills"), 1.0)
        self.assertGreater(ob.fuzzy_score("skills", "Your Skills"), 0.8)
        self.assertGreater(ob.fuzzy_score("graveyard zombies", "Graveyard Zombie"), 0.9)
        self.assertLess(ob.fuzzy_score("bank", "Fast Travel"), 0.5)
        hit, s = ob.best_match("farm merchant", ["Fish Merchant", "Farm Merchant", "Mine Merchant"])
        self.assertEqual(hit, "Farm Merchant")


class HudTests(unittest.TestCase):
    def test_vitals(self):
        v = ob.parse_vitals("§c100/100❤     §a0❈ Defense     §b100/100✎ Mana")
        self.assertEqual((v["hp"], v["max_hp"], v["defense"], v["mana"]), (100, 100, 0, 100))
        v = ob.parse_vitals("1,250/2.5k❤     +5 Mining (120/175)     80/100✎ Mana")
        self.assertEqual((v["hp"], v["max_hp"]), (1250, 2500))
        self.assertEqual(v["xp_popup"], "+5 Mining (120/175)")
        self.assertEqual(ob.parse_vitals(""), {})

    def test_player_hp_fallbacks(self):
        st = {"actionBar": {"text": ""}, "player": {"health": 10.0, "maxHealth": 20.0}}
        self.assertEqual(ob.player_hp(st), (50.0, 100.0))
        self.assertEqual(ob.player_hp(st, {"health": 70, "maxHealth": 140}), (70.0, 140.0))

    def test_sidebar(self):
        sb = ob.parse_sidebar(["10/03/26 m1A", " ", " Early Summer 5th", " ☽ 1:10am", " ⏣ Village Square", " ",
                               "Purse: 1,234.5", "Bits: 0", " ", "Objective", "Talk to your Island Helper", " ",
                               "www.skyclone.example"])
        self.assertEqual(sb["purse"], 1234.5)
        self.assertEqual(sb["zone"], "Village Square")
        self.assertEqual(sb["objective"], "Talk to your Island Helper")
        self.assertNotIn("www.skyclone.example", sb["lines"])

    def test_island_and_compass(self):
        self.assertEqual(ob.island_of("minecraft:sb_hub"), "hub")
        self.assertEqual(ob.island_of("minecraft:sb_private_island"), "private_island")
        self.assertEqual(ob.compass(0, -10), "N")
        self.assertEqual(ob.compass(10, 0), "E")
        self.assertEqual(ob.compass(-5, 5), "SW")
        self.assertEqual(ob.facing(180), "N")
        self.assertEqual(ob.facing(-90), "E")


class EntityTests(unittest.TestCase):
    def test_mob_nameplates(self):
        e = {"type": "minecraft:zombie", "nameplate": "[Lv1] Graveyard Zombie 100/100❤", "health": 20}
        mi = ob.mob_info(e)
        self.assertEqual((mi["name"], mi["level"], mi["hp"], mi["max_hp"]), ("Graveyard Zombie", 1, 100, 100))
        self.assertEqual(ob.entity_kind(e), "mob")
        boss = {"type": "minecraft:zombie", "nameplate": "☠ Ghoul Horror 50M❤"}
        self.assertTrue(ob.mob_info(boss)["boss"])
        self.assertEqual(ob.mob_info({"nameplate": "[Lv60] Golden Ghoul 45k/45k❤"})["max_hp"], 45000)

    def test_npc_name_is_the_bottom_label(self):
        e = {"type": "minecraft:mannequin", "nameplate": "HUB GUIDE | Lost? Click me! | Town Crier Hollis", "health": 20.0}
        self.assertEqual(ob.entity_kind(e), "npc")
        self.assertEqual(ob.entity_name(e), "Town Crier Hollis")

    def test_labels_items_players(self):
        self.assertEqual(ob.entity_kind({"type": "minecraft:text_display", "text": "x"}), "label")
        self.assertEqual(ob.entity_kind({"type": "minecraft:item"}), "item")
        self.assertEqual(ob.entity_kind({"type": "minecraft:player", "isPlayer": True, "name": "Ren"}, {"Ren"}), "player")
        self.assertEqual(ob.entity_kind({"type": "minecraft:player", "isPlayer": True, "name": "Bob"}, {"Ren"}), "npc")
        self.assertEqual(ob.entity_kind({"type": "minecraft:cow", "health": 10}), "creature")

    @unittest.skipUnless(SAMPLE_STATE.is_file(), "TestPilot sample state not present")
    def test_real_testpilot_state(self):
        st = json.loads(SAMPLE_STATE.read_text(encoding="utf-8"))
        g = ob.classify(st)
        names = {ob.entity_name(e) for e in g["npc"]}
        self.assertIn("Town Crier Hollis", names)
        self.assertIn("Old Osric", names)
        self.assertFalse(g["label"] and any(e.get("type") == "minecraft:mannequin" for e in g["label"]))
        self.assertIn("SkyBlock Menu", ob.item_label(st["hotbar"][8]))
        self.assertEqual(ob.sky_id(st["hotbar"][0]), "COBBLESTONE_GENERATOR_1")


class LayoutTests(unittest.TestCase):
    REPORT = """LAYOUT  3 anchors, 2 areas, 2 zones, 0 data
  anchor spawn                                 -24.5   73.0      0.5  yaw -90
  anchor npc.island_helper                     -21.5   73.0      3.5  yaw 135
  area   portal.hub                         [-35,73,-1 -> -35,76,1]
  area   quarry                             [16,69,13 -> 30,76,25]
  zone   private_island       &aPrivate Island         [-120,0,-120 -> 120,319,120] [BUILD]
  zone   tiny                 &bTiny Hut               [0,60,0 -> 4,90,4] [SAFE, NO_MOBS]
"""

    def test_parse_report(self):
        L = ob.Layouts.parse_report(self.REPORT)
        self.assertEqual(L["anchors"]["npc.island_helper"], (-21.5, 73.0, 3.5))
        self.assertEqual(L["areas"]["quarry"], (16, 69, 13, 30, 76, 25))
        self.assertEqual(L["zones"][1]["flags"], ["SAFE", "NO_MOBS"])

    def test_instanced_origin_and_zone(self):
        lay = ob.Layouts(dirs=[], previews=None)
        lay._cache["private_island"] = ob.Layouts.parse_report(self.REPORT) | {"source": "t"}
        self.assertEqual(lay.origin("private_island", (2030.0, 70, -1020.0)), (2048, -1024))
        self.assertEqual(lay.origin("hub", (2030.0, 70, -1020.0)), (0.0, 0.0))
        a = lay.anchors_world("private_island", (2030.0, 70, -1020.0))
        self.assertEqual(a["spawn"], (2048 - 24.5, 73.0, -1024 + 0.5))
        self.assertEqual(lay.zone_at("private_island", (2050, 70, -1022))["id"], "tiny")   # smallest wins
        self.assertEqual(lay.zone_at("private_island", (2000, 70, -1000))["id"], "private_island")

    @unittest.skipUnless(Path(SHOW.paths["skyclone"]).is_dir(), "SkyClone not present")
    def test_real_hub_places(self):
        L = ob.LAYOUTS
        pos = (0.5, 75, 12.5)
        self.assertEqual(L.find_place("hub", "Farm Merchant", pos)[0], "npc.farm_merchant")
        self.assertEqual(L.find_place("hub", "graveyard", pos)[0], "warp.graveyard")     # the safe gate
        self.assertEqual(L.find_place("hub", "banker", pos)[0], "npc.banker")
        self.assertEqual(L.find_place("hub", "coal mine", pos)[0], "warp.coal_mine")
        self.assertIsNone(L.find_place("hub", "the moon base xyz", pos))
        near = [n for n, _, _ in L.nearby("hub", pos)]
        self.assertIn("Town Crier Hollis", near)
        self.assertEqual(L.zone_at("hub", (-34, 76, -36))["id"], "bank")

    @unittest.skipUnless(Path(SHOW.paths["skyclone"]).is_dir(), "SkyClone not present")
    def test_npc_index_from_data(self):
        idx = ob.LAYOUTS.npc_index()
        self.assertEqual(idx.get("farm merchant"), "npc.farm_merchant")
        self.assertEqual(idx.get("gerald"), "npc.island_helper")
        self.assertEqual(idx.get("town crier hollis"), "npc.hub_guide")


class DigestTests(unittest.TestCase):
    def test_digest_sections_and_chat(self):
        w = MockWorld(["Kaito", "Ren"])
        c = w.ctx("Kaito")
        w.say_to("Kaito", "[0] Ren: yo where are you")
        w.say_to("Kaito", "[0] Kaito: on my island")
        w.say_to("Kaito", "▬▬▬▬▬▬▬▬▬▬")
        d, n = ob.observe(c, 0, extra="TIME: 29:00 left")
        for key in ("TIME: 29:00 left", "WHERE: Private Island", "YOU: 100/100 HP", "HOLDING:", "HOTBAR:",
                    "INVENTORY", "NPCS: Gerald", "PLAYERS: Ren", "NEW CHAT:", "OBJECTIVE:"):
            self.assertIn(key, d)
        self.assertIn("(you) [0] Kaito: on my island", d)
        self.assertNotIn("▬▬▬", d)
        self.assertEqual(n, len(w.players["Kaito"]["chat"]))
        d2, n2 = ob.observe(c, n)
        self.assertNotIn("NEW CHAT", d2)
        self.assertEqual(n2, n)
        self.assertLess(len(d), 6000)

    def test_digest_open_screen_and_mobs(self):
        w = MockWorld(["Kaito"])
        c = w.ctx("Kaito")
        c.pilot.chat("/hub")
        c.pilot.walk_to(150, 76, -20, timeout=60)
        c.pilot.chat("/sbmenu")
        d, _ = ob.observe(c, 0)
        self.assertIn("OPEN SCREEN: 'SkyBlock Menu'", d)
        self.assertIn("Lv1 Graveyard Zombie x", d)

    def test_observe_never_raises(self):
        class Broken:
            def state(self, **kw):
                raise RuntimeError("client gone")
        class Ctx:
            pilot = Broken()
            char = SHOW.characters[0]
            def log(self, m): pass
            def showcontrol_state(self): return {}
        d, n = ob.observe(Ctx(), 7, extra="TIME")
        self.assertIn("observation failed", d)
        self.assertEqual(n, 7)


def naive_png(w, h, rows, chans, filters):
    """Reference encoder that applies a chosen filter per row (all five types)."""
    bpp = chans
    out = bytearray()
    prev = bytes(w * chans)
    for y, r in enumerate(rows):
        f = filters[y % len(filters)]
        out.append(f)
        for i in range(len(r)):
            a = r[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 0:
                pr = 0
            elif f == 1:
                pr = a
            elif f == 2:
                pr = b
            elif f == 3:
                pr = (a + b) >> 1
            else:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
            out.append((r[i] - pr) & 255)
        prev = r
    ctype = {3: 2, 4: 6}[chans]

    def chunk(t, b):
        return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(out))) + chunk(b"IEND", b""))


class PngTests(unittest.TestCase):
    def test_swar(self):
        a, b = os.urandom(777), os.urandom(777)
        self.assertEqual(ob._add_bytes(a, b), bytes((x + y) & 255 for x, y in zip(a, b)))
        self.assertEqual(ob._sub_bytes(a, b), bytes((x - y) & 255 for x, y in zip(a, b)))

    def test_decode_every_filter(self):
        for chans in (3, 4):
            w, h = 37, 11
            rows = [bytearray(os.urandom(w * chans)) for _ in range(h)]
            png = naive_png(w, h, rows, chans, [0, 1, 2, 3, 4])
            W, H, C, got = ob.decode_png(png)
            self.assertEqual((W, H, C), (w, h, chans))
            self.assertEqual([bytes(r) for r in got], [bytes(r) for r in rows])

    def test_encode_roundtrip(self):
        w, h = 50, 9
        rows = [os.urandom(w * 3) for _ in range(h)]
        W, H, C, got = ob.decode_png(ob.encode_png(w, h, rows, 3))
        self.assertEqual((W, H, C), (w, h, 3))
        self.assertEqual([bytes(r) for r in got], rows)

    def test_downscale_width_and_colour(self):
        w, h = 1904, 12
        rows = [bytes([200, 100, 50, 255] * w) for _ in range(h)]
        out = ob.downscale_png(naive_png(w, h, rows, 4, [4, 1]), 768)
        W, H, C, got = ob.decode_png(out)
        self.assertLessEqual(W, 768)
        self.assertEqual(C, 3)
        self.assertEqual(bytes(got[0][:3]), bytes([200, 100, 50]))

    def test_rejects_garbage(self):
        with self.assertRaises(ob.PngError):
            ob.decode_png(b"not a png at all")

    @unittest.skipUnless(SAMPLE_SHOT.is_file(), "TestPilot sample screenshot not present")
    def test_real_minecraft_screenshot(self):
        out = ob.downscale_png(SAMPLE_SHOT.read_bytes(), 768)
        W, H, C, _ = ob.decode_png(out)
        self.assertEqual((W, H, C), (640, 360, 3))

    def test_screenshot_block(self):
        w = MockWorld(["Kaito"])
        c = w.ctx("Kaito")
        blk = ob.screenshot_block(c, max_w=768)
        self.assertEqual(blk["type"], "image")
        self.assertEqual(blk["source"]["media_type"], "image/png")
        W, H, C, _ = ob.decode_png(base64.b64decode(blk["source"]["data"]))
        self.assertLessEqual(W, 768)

    def test_screenshot_failure_returns_none(self):
        class P:
            def screenshot(self, path):
                raise RuntimeError("no client")
        class Ctx:
            pilot = P()
            char = SHOW.characters[0]
            def log(self, m): pass
        self.assertIsNone(ob.screenshot_block(Ctx()))


if __name__ == "__main__":
    unittest.main()
