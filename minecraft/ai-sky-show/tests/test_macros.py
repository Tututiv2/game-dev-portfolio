"""Macros against the mock world (no Minecraft): interface, arg binding, every tool's happy path and
the failure messages the model relies on.   py -3 -m unittest discover -s tests"""
import sys
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "orchestrator"))

from show import macros as M  # noqa: E402
from show.macros import TOOLS, describe_tools, run_action  # noqa: E402
from show.mock import MockWorld, make  # noqa: E402

CONTRACT_TOOLS = ["look_around", "walk_to", "warp", "open_menu", "click_item", "close_menu", "talk_to", "fight",
                  "mine", "chop", "farm", "fish", "sell_to_npc", "craft", "buy_from_npc", "bazaar", "equip",
                  "use_ability", "say", "wait", "command"]


def fresh(account="Kaito"):
    w = MockWorld([account])
    return w, w.ctx(account)


def give_kit(ctx):
    for sky in ("ROOKIE_PICKAXE", "ROOKIE_AXE", "ROOKIE_HOE"):
        ctx.pilot._give(sky, 1)


class InterfaceTests(unittest.TestCase):
    def test_contract_tools_exist(self):
        for t in CONTRACT_TOOLS:
            self.assertIn(t, TOOLS, t)
            self.assertTrue(callable(TOOLS[t].fn))
            self.assertTrue(TOOLS[t].doc)

    def test_describe_tools_is_compact_and_complete(self):
        d = describe_tools()
        for t in CONTRACT_TOOLS:
            self.assertIn(f"- {t}(", d)
        self.assertLess(len(d), 4500)

    def test_unknown_tool_never_raises(self):
        _, c = fresh()
        r = run_action(c, "teleport_to_moon", {})
        self.assertFalse(r["ok"])
        self.assertIn("unknown tool", r["result"])

    def test_bad_args_never_raise(self):
        _, c = fresh()
        for args in ({"x": "banana"}, {"count": "lots"}, ["a", "b", "c", "d", "e", "f", "g"], None, 42):
            r = run_action(c, "fight", args)
            self.assertIn("ok", r)
            self.assertIsInstance(r["result"], str)

    def test_result_is_short(self):
        _, c = fresh()
        r = run_action(c, "look_around", {})
        self.assertLessEqual(len(r["result"]), M.RESULT_MAX)

    def test_arg_aliases_and_positional(self):
        t = TOOLS["fight"]
        self.assertEqual(M._bind_args(t, {"mob": "zombie", "amount": 3})["target"], "zombie")
        self.assertEqual(M._bind_args(TOOLS["warp"], "hub"), {"dest": "hub"})
        self.assertEqual(M._bind_args(TOOLS["warp"], {"destination": "barn"}), {"dest": "barn"})
        self.assertEqual(M._bind_args(TOOLS["say"], {"message": "hi"}), {"text": "hi"})
        self.assertEqual(M._bind_args(TOOLS["walk_to"], {"anchor": "npc.banker"}), {"place": "npc.banker"})
        self.assertEqual(M._bind_args(TOOLS["click_item"], {"name": "Your Skills"}), {"item": "Your Skills"})
        self.assertEqual(M._bind_args(TOOLS["open_menu"], {"name": "skills"}), {"name": "skills"})

    def test_tool_name_fuzzy(self):
        self.assertIs(M._resolve_tool("walk-to"), TOOLS["walk_to"])
        self.assertIs(M._resolve_tool("Look Around"), TOOLS["look_around"])

    def test_timeout_is_enforced(self):
        @M.tool("_spin_test", "test only", {}, timeout=1.0)
        def _spin(g):
            while True:
                g.check()
                time.sleep(0.05)
        try:
            _, c = fresh()
            t0 = time.time()
            r = run_action(c, "_spin_test", {})
            self.assertFalse(r["ok"])
            self.assertIn("ran out of time", r["result"])
            self.assertLess(time.time() - t0, 3)
        finally:
            TOOLS.pop("_spin_test", None)

    def test_stop_event_ends_macro(self):
        _, c = fresh()
        c.stop_event.set()
        r = run_action(c, "fish", {"seconds": 20})
        self.assertFalse(r["ok"])

    def test_keys_released_after_movement(self):
        _, c = fresh()
        run_action(c, "walk_to", {"place": "npc.island_helper"})
        self.assertIn(("stop", {}), c.pilot.calls)


class ChatTests(unittest.TestCase):
    def test_say_sanitises(self):
        _, c = fresh()
        r = run_action(c, "say", {"text": "/op Kaito\nhello  §cworld " + "x" * 400})
        self.assertTrue(r["ok"])
        sent = [b["text"] for k, b in c.pilot.calls if k == "chat"][-1]
        self.assertFalse(sent.startswith("/"))
        self.assertNotIn("\n", sent)
        self.assertNotIn("§", sent)
        self.assertLessEqual(len(sent), 200)

    def test_command_whitelist(self):
        _, c = fresh()
        for bad in ("/sb give Kaito ASPECT_OF_THE_END", "/op Kaito", "/gamemode creative", "/tp 0 0 0", "/kill"):
            r = run_action(c, "command", {"text": bad})
            self.assertFalse(r["ok"], bad)
        self.assertFalse(any(k == "chat" for k, _ in c.pilot.calls))
        r = run_action(c, "command", {"text": "/questlog"})
        self.assertTrue(r["ok"])
        self.assertIn("Quest Log", r["result"])


class FirstThirtyMinutesFlow(unittest.TestCase):
    """The new-player loop the primer teaches, end to end on the mock."""

    def test_flow(self):
        w, c = fresh()
        p = c.pilot
        r = run_action(c, "talk_to", {"npc": "Gerald"})
        self.assertTrue(r["ok"], r)
        self.assertIn("Gerald", r["result"])
        self.assertIn("Starter Kit", r["result"])        # second click opened his menu
        self.assertEqual(p._count("ROOKIE_AXE"), 1)
        run_action(c, "close_menu")
        r = run_action(c, "chop", {"block": "oak", "count": 3})
        self.assertTrue(r["ok"], r)
        self.assertGreaterEqual(p._count("OAK_LOG"), 3)
        r = run_action(c, "craft", {"item": "oak planks", "n": 2})
        self.assertTrue(r["ok"], r)
        self.assertEqual(p._count("OAK_PLANKS"), 8)
        r = run_action(c, "craft", {"item": "wooden pickaxe"})
        self.assertFalse(r["ok"])
        self.assertIn("missing materials", r["result"])
        self.assertIn("Stick", r["result"])
        self.assertTrue(run_action(c, "craft", {"item": "stick"})["ok"])
        r = run_action(c, "craft", {"item": "wooden pickaxe"})
        self.assertTrue(r["ok"], r)
        self.assertEqual(p._count("WOODEN_PICKAXE"), 1)
        r = run_action(c, "mine", {"block": "coal", "count": 2})
        self.assertTrue(r["ok"], r)
        self.assertIn("+2 Coal", r["result"])
        r = run_action(c, "place_minion")
        self.assertTrue(r["ok"], r)
        r = run_action(c, "open_menu", {"name": "skyblock"})
        self.assertTrue(r["ok"], r)
        self.assertIn("Your Skills", r["result"])
        r = run_action(c, "click_item", {"name": "skills"})
        self.assertIn("Your Skills", r["result"])
        r = run_action(c, "warp", {"dest": "hub"})
        self.assertTrue(r["ok"], r)
        self.assertEqual(p.me["island"], "hub")


class MovementTests(unittest.TestCase):
    def test_walk_to_place_and_coordinates(self):
        _, c = fresh()
        c.pilot.chat("/hub")
        r = run_action(c, "walk_to", {"place": "Farm Merchant"})
        self.assertTrue(r["ok"], r)
        me = c.pilot.me
        self.assertLess(abs(me["x"] - 105.5) + abs(me["z"] - 108.5), 4)
        r = run_action(c, "walk_to", {"x": 10, "y": 75, "z": 10})
        self.assertTrue(r["ok"], r)

    def test_walk_to_unknown_place(self):
        _, c = fresh()
        r = run_action(c, "walk_to", {"place": "the moon base"})
        self.assertFalse(r["ok"])
        self.assertIn("known nearby", r["result"])

    def test_stuck_recovery_uses_move(self):
        _, c = fresh()
        c.pilot.chat("/hub")
        c.pilot.me["stuck"] = 2               # first two walk_to calls make no progress
        r = run_action(c, "walk_to", {"x": 10, "y": 75, "z": -15})
        self.assertTrue(r["ok"], r)
        self.assertTrue(any(k == "move" for k, _ in c.pilot.calls))

    def test_stuck_forever_reports(self):
        _, c = fresh()
        c.pilot.chat("/hub")
        c.pilot.me["stuck"] = 999
        c.pilot.move = lambda **kw: {"ok": True}  # recovery cannot move us either
        r = run_action(c, "walk_to", {"x": 10, "y": 75, "z": -15})
        self.assertFalse(r["ok"])
        self.assertIn("stuck", r["result"])

    def test_hub_route_goes_round_the_village(self):
        pts = M.plan_route("hub", (0.5, 75, 12.5), (144.5, 76, -23.5))
        self.assertEqual(pts[-1], (144.5, 76, -23.5))
        self.assertGreaterEqual(len(pts), 3)
        self.assertTrue(all(abs(p[2]) < 5 for p in pts[:-1]))   # east along the avenue
        self.assertEqual(M.plan_route("private_island", (0, 70, 0), (40, 70, 0)), [(40, 70, 0)])
        self.assertEqual(M.plan_route("hub", (0, 75, 0), (5, 75, 5)), [(5, 75, 5)])

    def test_route_leaves_buildings_by_the_door(self):
        pts = M.plan_route("hub", (-36.5, 75, -32.5), (144.5, 76, -23.5))   # from inside the Bank
        self.assertEqual(pts[0][:3:2], (-33.5, -26.5))                       # door.bank first


class WarpTests(unittest.TestCase):
    def test_warp_ids(self):
        cases = {"hub": "hub", "island": "home", "my island": "home", "graveyard": "hub_graveyard",
                 "Spider's Den": "spiders_den", "the end": "the_end", "crypts": "crypt", "coal mine": "hub_coal_mine",
                 "Gold Mine": "gold_mine", "hub_farm": "hub_farm", "deep caverns": "deep_caverns"}
        for k, v in cases.items():
            self.assertEqual(M.warp_id(k), v, k)

    def test_locked_district_gives_a_way_in(self):
        _, c = fresh()
        c.pilot.chat("/hub")
        r = run_action(c, "warp", {"dest": "graveyard"})
        self.assertFalse(r["ok"])
        self.assertIn("Requires", r["result"])
        self.assertIn("walk_to", r["result"])

    def test_warp_after_discovery(self):
        _, c = fresh()
        c.pilot.chat("/hub")
        self.assertTrue(run_action(c, "walk_to", {"place": "graveyard"})["ok"])
        run_action(c, "warp", {"dest": "hub"})
        r = run_action(c, "warp", {"dest": "graveyard"})
        self.assertTrue(r["ok"], r)

    def test_skill_locked_island(self):
        _, c = fresh()
        r = run_action(c, "warp", {"dest": "barn"})
        self.assertFalse(r["ok"])
        self.assertIn("Farming", r["result"])


class CombatTests(unittest.TestCase):
    def setUp(self):
        self.w, self.c = fresh()
        self.c.pilot.chat("/hub")
        run_action(self.c, "walk_to", {"place": "graveyard"})

    def test_fight_counts_kills_and_uses_best_weapon(self):
        self.c.pilot._give("UNDEAD_SWORD", 1)
        self.c.pilot._give("ROOKIE_PICKAXE", 1)
        r = run_action(self.c, "fight", {"target": "Graveyard Zombie", "count": 2})
        self.assertTrue(r["ok"], r)
        self.assertIn("killed 2", r["result"])
        self.assertIn("Undead Sword", r["result"])
        self.assertGreater(self.c.pilot.me["purse"], 0)

    def test_fight_nearest_hostile(self):
        r = run_action(self.c, "fight", {"target": "", "count": 1})
        self.assertTrue(r["ok"], r)

    def test_fight_retreats_when_low(self):
        self.c.pilot.me["hp"] = 25
        r = run_action(self.c, "fight", {"target": "zombie", "count": 3})
        self.assertFalse(r["ok"])
        self.assertIn("retreated", r["result"])

    def test_fight_no_target(self):
        r = run_action(self.c, "fight", {"target": "Enderman", "count": 1})
        self.assertFalse(r["ok"])
        self.assertIn("Graveyard Zombie", r["result"])      # tells what IS here


class GatherTests(unittest.TestCase):
    def test_block_patterns(self):
        self.assertEqual(M.block_pattern("mine", "coal"), "coal_ore")
        self.assertEqual(M.block_pattern("mine", "cobblestone"), "cobblestone")
        self.assertEqual(M.block_pattern("mine", "ore"), "_ore")
        self.assertEqual(M.block_pattern("chop", ""), "_log")
        self.assertEqual(M.block_pattern("chop", "birch logs"), "birch_log")
        self.assertEqual(M.block_pattern("farm", "potatoes"), "potatoes")
        self.assertEqual(M.block_pattern("farm", "carrot"), "carrots")
        self.assertEqual(M.block_pattern("farm", "sugar cane"), "sugar_cane")

    def test_mine_needs_breaking_power_message(self):
        _, c = fresh()
        c.pilot.chat("/hub")
        run_action(c, "walk_to", {"x": 62, "y": 76, "z": -147})
        r = run_action(c, "mine", {"block": "coal", "count": 2})
        self.assertFalse(r["ok"])
        self.assertIn("Breaking Power", r["result"])

    def test_mine_in_hub_leaves_bedrock(self):
        w, c = fresh()
        give_kit(c)
        c.pilot.chat("/hub")
        run_action(c, "walk_to", {"x": 62, "y": 76, "z": -147})
        r = run_action(c, "mine", {"block": "coal", "count": 2})
        self.assertTrue(r["ok"], r)
        self.assertIn("bedrock", w.blocks["hub"].values())

    def test_nothing_to_gather_gives_hints(self):
        _, c = fresh()
        c.pilot.chat("/hub")
        r = run_action(c, "chop", {"block": "oak", "count": 2})
        self.assertFalse(r["ok"])
        self.assertIn("Forest", r["result"])

    def test_farm(self):
        _, c = fresh()
        r = run_action(c, "farm", {"block": "wheat", "count": 2})
        self.assertTrue(r["ok"], r)
        self.assertGreaterEqual(c.pilot._count("WHEAT"), 2)


class FishingTests(unittest.TestCase):
    def test_no_rod(self):
        _, c = fresh()
        r = run_action(c, "fish", {"seconds": 5})
        self.assertFalse(r["ok"])
        self.assertIn("Fisher Gideon", r["result"])

    def test_fish_catches(self):
        _, c = fresh()
        c.pilot._give("FISHING_ROD", 1)
        run_action(c, "walk_to", {"x": 1024 + 2, "y": 73, "z": 10})
        r = run_action(c, "fish", {"seconds": 9})
        self.assertTrue(r["ok"], r)
        self.assertGreaterEqual(c.pilot._count("RAW_FISH"), 1)


class ShopTests(unittest.TestCase):
    def setUp(self):
        self.w, self.c = fresh()
        give_kit(self.c)
        self.c.pilot.chat("/hub")
        run_action(self.c, "walk_to", {"place": "Farm Merchant"})

    def test_sell_all_keeps_tools(self):
        self.c.pilot._give("COAL", 10)
        self.c.pilot._give("OAK_LOG", 5)
        r = run_action(self.c, "sell_to_npc", {"all": True})
        self.assertTrue(r["ok"], r)
        self.assertEqual(self.c.pilot._count("COAL"), 0)
        self.assertEqual(self.c.pilot._count("ROOKIE_PICKAXE"), 1)
        self.assertEqual(self.c.pilot._count("COBBLESTONE_GENERATOR_1"), 1)
        self.assertGreater(self.c.pilot.me["purse"], 0)

    def test_sell_named_item(self):
        self.c.pilot._give("COAL", 4)
        self.c.pilot._give("OAK_LOG", 5)
        r = run_action(self.c, "sell_to_npc", {"item": "coal"})
        self.assertTrue(r["ok"], r)
        self.assertEqual(self.c.pilot._count("OAK_LOG"), 5)

    def test_buy_needs_coins(self):
        r = run_action(self.c, "buy_from_npc", {"npc": "Farm Merchant", "item": "Rookie Hoe"})
        self.assertFalse(r["ok"])
        self.assertIn("enough coins", r["result"])
        self.c.pilot.me["purse"] = 50
        r = run_action(self.c, "buy_from_npc", {"npc": "Farm Merchant", "item": "Rookie Hoe"})
        self.assertTrue(r["ok"], r)
        self.assertIn("50 -> 40", r["result"])

    def test_buy_unknown_item_lists_stock(self):
        r = run_action(self.c, "buy_from_npc", {"npc": "Farm Merchant", "item": "Aspect of the Dragons"})
        self.assertFalse(r["ok"])
        self.assertIn("Wheat", r["result"])

    def test_no_merchant_nearby(self):
        _, c = fresh()
        c.pilot._give("COAL", 3)
        r = run_action(c, "sell_to_npc", {"all": True})
        self.assertFalse(r["ok"])
        self.assertIn("Farm Merchant", r["result"])


class NpcAndMenuTests(unittest.TestCase):
    def test_talk_to_walks_to_far_npc(self):
        _, c = fresh()
        c.pilot.chat("/hub")
        r = run_action(c, "talk_to", {"npc": "weaponsmith"})
        self.assertTrue(r["ok"], r)
        self.assertIn("Undead Sword", r["result"])

    def test_talk_to_unknown(self):
        _, c = fresh()
        r = run_action(c, "talk_to", {"npc": "Elvis"})
        self.assertFalse(r["ok"])
        self.assertIn("Gerald", r["result"])                # lists who IS here

    def test_click_without_menu(self):
        _, c = fresh()
        r = run_action(c, "click_item", {"name": "Your Skills"})
        self.assertFalse(r["ok"])

    def test_click_missing_button_lists_buttons(self):
        _, c = fresh()
        run_action(c, "open_menu", {"name": "skyblock"})
        r = run_action(c, "click_item", {"name": "Teleporter 9000"})
        self.assertFalse(r["ok"])
        self.assertIn("Fast Travel", r["result"])

    def test_equip_from_inventory_moves_to_hotbar(self):
        _, c = fresh()
        inv = c.pilot.me["inv"]
        inv[20] = make("UNDEAD_SWORD")
        for i in range(8):
            inv[i] = inv[i] or make("COAL", 1)
        r = run_action(c, "equip", {"item": "undead sword"})
        self.assertTrue(r["ok"], r)
        sel = c.pilot.me["sel"]
        self.assertEqual(c.pilot.me["inv"][sel]["name"], "Undead Sword")

    def test_wait_reports_chat(self):
        w, c = fresh()
        threading.Timer(0.3, lambda: w.say_to("Kaito", "[0] Ren: hurry up")).start()
        r = run_action(c, "wait", {"seconds": 1})
        self.assertIn("hurry up", r["result"])


if __name__ == "__main__":
    unittest.main()
