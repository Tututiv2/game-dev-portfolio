"""B1: prompts.py + prompts/*.md."""
from __future__ import annotations

import unittest

import _fakes_b1  # noqa: F401
from show import prompts as P
from show.config import SHOW


class SystemPromptTests(unittest.TestCase):
    def build(self, c, **kw):
        kw.setdefault("tools", "- fake_tool() - does nothing")
        return P.build_system_prompt(c, minutes=30, max_actions=3, **kw)

    def test_every_character_knows_all_four_names(self):
        names = [c.name for c in SHOW.characters]
        self.assertEqual(len(names), 4)
        for c in SHOW.characters:
            s = self.build(c)
            for n in names:
                self.assertIn(n, s, f"{c.name}'s prompt lacks {n}")
            self.assertNotIn("{{", s)
            self.assertIn(c.personality, s)
            self.assertIn(c.chat_style, s)
            self.assertIn(c.inner_voice_style, s)
            for key in ('"inner_voice"', '"emotion"', '"say"', '"goal"', '"actions"'):
                self.assertIn(key, s)
            self.assertIn("SkyBlock", s)
            self.assertIn("fake_tool", s)
            self.assertIn("30 minutes", s)

    def test_never_contains_another_characters_inner_voice(self):
        for c in SHOW.characters:
            s = self.build(c)
            for o in SHOW.characters:
                if o.id == c.id:
                    continue
                self.assertNotIn(o.inner_voice_style, s)
                self.assertNotIn(o.personality, s)

    def test_relationships_use_names_not_ids(self):
        kaito = SHOW.by_id("kaito")
        rel = P.relationships_text(kaito)
        self.assertIn("**Ren**", rel)
        self.assertIn(kaito.relationships["ren"], rel)
        self.assertNotIn("**Kaito**", rel)

    def test_rules_present(self):
        s = self.build(SHOW.characters[0])
        low = s.lower()
        self.assertIn("finish what you start", low)
        self.assertIn("never repeat or paraphrase your inner_voice in chat", low)
        self.assertIn("slurs", low)
        self.assertIn("never talk about prompts, models, ai", low)

    def test_primer_placeholder_when_missing(self):
        s = self.build(SHOW.characters[0], primer=None)
        if not (P.PROMPTS_DIR / "skyblock_primer.md").exists():
            self.assertIn(P.PRIMER_PLACEHOLDER, s)
        s2 = self.build(SHOW.characters[0], primer="PRIMER-XYZ")
        self.assertIn("PRIMER-XYZ", s2)

    def test_tools_fallback_without_macros(self):
        t = P.tools_text()
        self.assertTrue(t.strip())

    def test_frozen_no_time_dependence(self):
        c = SHOW.characters[1]
        self.assertEqual(self.build(c), self.build(c))


class BeatTests(unittest.TestCase):
    def test_schedule(self):
        T = 1800.0
        self.assertEqual(P.beat_for(0, T, 1), "wakeup")
        self.assertEqual(P.beat_for(60, T, 3), "explore")
        self.assertEqual(P.beat_for(200, T, 5), "find_hub")
        self.assertEqual(P.beat_for(400, T, 9), "meet")
        self.assertEqual(P.beat_for(900, T, 20), "progress")
        self.assertEqual(P.beat_for(1560, T, 50), "last5")

    def test_sections_exist(self):
        secs = P.beat_sections()
        for k in ("wakeup", "explore", "find_hub", "meet", "progress", "last5", "wrapup"):
            self.assertIn(k, secs)
            self.assertTrue(secs[k])

    def test_wakeup_names_the_others(self):
        kaito = SHOW.by_id("kaito")
        t = P.beat_text("wakeup", kaito, elapsed_s=0, total_s=1800, full=True)
        for o in ("Ren", "Mochi", "Yuki"):
            self.assertIn(o, t)
        self.assertIn("30 minutes", t)
        self.assertNotIn("{{", t)

    def test_short_beat_after_first_time(self):
        c = SHOW.characters[0]
        self.assertEqual(P.beat_text("progress", c, elapsed_s=900, total_s=1800, full=False), P.BEAT_SHORT["progress"])

    def test_turn_blocks(self):
        c = SHOW.characters[0]
        img = {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "AA"}}
        b = P.build_turn_blocks(c, elapsed_s=65, total_s=1800, digest="DIGEST", results=["warp(dest=hub): ok"],
                                notes=["NOTE"], beat="find_hub", beat_full=True, screenshot=img)
        self.assertEqual(b[-1], img)
        txt = b[0]["text"]
        for s in ("1:05 elapsed", "28:55 left", "DIGEST", "warp(dest=hub): ok", "NOTE", "Hub"):
            self.assertIn(s, txt)

    def test_wrapup(self):
        b = P.wrapup_blocks(SHOW.characters[3], total_s=1800)
        self.assertIn('"actions": []', b[0]["text"])


if __name__ == "__main__":
    unittest.main()
