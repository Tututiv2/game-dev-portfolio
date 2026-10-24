"""Client launch command construction + the kill matcher. Nothing is launched or killed here."""
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "orchestrator"))

from show import clients  # noqa: E402
from show.config import SHOW  # noqa: E402


class PlanTests(unittest.TestCase):
    def test_five_clients(self):
        specs = clients.plan()
        self.assertEqual(len(specs), 5)
        self.assertEqual([s.account for s in specs[:4]], [c.account for c in SHOW.characters])
        self.assertEqual(specs[4].account, SHOW.camera_account)
        self.assertEqual([s.port for s in specs], SHOW.ports["pilot"] + [SHOW.ports["pilot_camera"]])
        self.assertEqual(len(clients.plan(include_camera=False)), 4)

    def test_command_matches_pilot_launch(self):
        s = clients.plan()[0]
        self.assertEqual(s.cmd, ["py", "-3", "-m", "launcher", "launch", "Fabric-26.1.2", "--dev-user",
                                 SHOW.characters[0].account, "--server", f"localhost:{SHOW.ports['server']}", "--detach"])
        self.assertEqual(s.cwd, SHOW.paths["launcher"])
        self.assertIn("localhost:25640", s.cmd)

    def test_player_env(self):
        tiles = SHOW.monitor["tiles"]
        for c, s in zip(SHOW.characters, clients.plan()):
            e = s.env_add
            self.assertEqual(e["TESTPILOT"], "1")
            self.assertEqual(e["TESTPILOT_PORT"], str(c.pilot_port))
            self.assertEqual(e["TESTPILOT_POS"], f"{tiles[c.slot][0]},{tiles[c.slot][1]}")
            self.assertEqual(e["TESTPILOT_WINDOW"], SHOW.monitor["tile_window"])
            self.assertEqual(e["TESTPILOT_FPS"], str(SHOW.client["fps"]))
            self.assertEqual(e["SHOWCAM_TITLE"], f"AISky - {c.name}")
            self.assertNotIn("SHOWCAM", e)
            self.assertNotIn("SHOWCAM_PORT", e)
            self.assertFalse(s.camera)

    def test_camera_env(self):
        s = clients.camera_spec()
        e = s.env_add
        self.assertTrue(s.camera)
        self.assertEqual(e["SHOWCAM"], "1")
        self.assertEqual(e["SHOWCAM_PORT"], str(SHOW.ports["showcam"]))
        self.assertEqual(e["SHOWCAM_PORT"], "25660")
        self.assertEqual(e["TESTPILOT_PORT"], str(SHOW.ports["pilot_camera"]))
        self.assertEqual(e["TESTPILOT_WINDOW"], SHOW.monitor["camera_window"])
        self.assertEqual(e["TESTPILOT_POS"], "{},{}".format(*SHOW.monitor["camera_pos"]))
        self.assertTrue(e["SHOWCAM_TITLE"].startswith("AISky - "))

    def test_env_merges_over_base(self):
        s = clients.plan()[1]
        env = s.env({"PATH": "x", "TESTPILOT": "0"})
        self.assertEqual(env["PATH"], "x")
        self.assertEqual(env["TESTPILOT"], "1")

    def test_spec_by_account(self):
        self.assertEqual(clients.spec_by_account(SHOW.characters[2].account.lower()).port, SHOW.ports["pilot"][2])
        with self.assertRaises(KeyError):
            clients.spec_by_account("Nobody")


class KillMatcherTests(unittest.TestCase):
    LISTING = "\n".join([
        "111\tjava.exe -cp x net.minecraft.client.main.Main --username Ren --version 26.1.2",
        "222\tjava.exe -cp x net.minecraft.client.main.Main --username Renato --version 26.1.2",
        "333\tjava.exe -jar paper.jar nogui",
        "444\tjava.exe Main --accessToken 0 --username Ren",
        "garbage line",
        "",
    ])

    def test_whole_token_only(self):
        self.assertEqual(clients.matching_pids(self.LISTING, "Ren"), [111, 444])
        self.assertEqual(clients.matching_pids(self.LISTING, "Renato"), [222])
        self.assertEqual(clients.matching_pids(self.LISTING, "Kaito"), [])

    def test_kill_uses_matcher(self):
        with mock.patch.object(clients, "_java_listing", return_value=self.LISTING), \
                mock.patch.object(clients.subprocess, "run") as run:
            killed = clients.kill("Ren")
        self.assertEqual(killed, [111, 444])
        cmds = [c.args[0] for c in run.call_args_list]
        self.assertEqual(cmds, [["taskkill", "/PID", "111", "/F"], ["taskkill", "/PID", "444", "/F"]])


class GuardTests(unittest.TestCase):
    def test_refuses_while_rocket_league_runs(self):
        with mock.patch.object(clients, "busy", return_value=["RocketLeague.exe"]), \
                mock.patch.object(clients.subprocess, "run", side_effect=AssertionError("must not launch")):
            with self.assertRaises(clients.LaunchError):
                clients.launch(clients.plan()[0], wait=False)

    def test_busy_parses_tasklist(self):
        out = '"System Idle Process","0","Services","0","8 K"\n"RocketLeague.exe","4242","Console","1","2,000,000 K"\n'
        with mock.patch.object(clients.subprocess, "run",
                               return_value=subprocess.CompletedProcess([], 0, stdout=out, stderr="")):
            self.assertEqual(clients.busy(), ["RocketLeague.exe"])

    def test_launch_runs_detached_command(self):
        spec = clients.plan()[0]
        done = subprocess.CompletedProcess(spec.cmd, 0, stdout="launched pid 1\n", stderr="")
        with mock.patch.object(clients, "busy", return_value=[]), \
                mock.patch("show.pilot_bridge.ready", return_value=False), \
                mock.patch.object(clients.subprocess, "run", return_value=done) as run:
            r = clients.launch(spec, wait=False, log=lambda m: None)
        self.assertTrue(r["ok"])
        args, kw = run.call_args
        self.assertEqual(args[0], spec.cmd)
        self.assertEqual(kw["cwd"], spec.cwd)
        self.assertEqual(kw["env"]["TESTPILOT_PORT"], str(spec.port))
        self.assertEqual(kw["env"]["SHOWCAM_TITLE"], spec.title)


if __name__ == "__main__":
    unittest.main()
