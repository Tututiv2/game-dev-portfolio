"""Unit tests for the three files where output quality actually lives.

No GPU, no video file, no window — BUILD_PROMPT.md §7 / §10.

    .venv\\Scripts\\python.exe -m unittest discover tests -v
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.core import layers as core_layers
from src.core.ass_writer import ass_time, build_ass
from src.core.grouping import Layout, balance_lines, group_words
from src.core.model import Caption, Layer, Project, Word
from src.core.reveal import MODES, render_card, word_spans, word_state
from src.core.srt_writer import build_srt
from src.core.themes import (
    Animation, Theme, ass_alpha, ass_colour, ass_style_colour,
)


def w(text, start, end, prob=1.0):
    return Word(text=text, start=start, end=end, prob=prob)


def three_word_card():
    words = [w("we", 1.0, 1.2), w("absolutely", 1.2, 1.9), w("did", 1.9, 2.1)]
    return Caption(words=words, start=0.95, end=2.25)


# ---------------------------------------------------------------------------


class TestColours(unittest.TestCase):
    def test_ass_is_bgr_not_rgb(self):
        self.assertEqual(ass_colour("#FFCC00"), "&H00CCFF&")
        self.assertEqual(ass_colour("#000000"), "&H000000&")
        self.assertEqual(ass_colour("#FF0000"), "&H0000FF&")   # red -> BB=00,GG=00,RR=FF

    def test_alpha_is_inverted(self):
        self.assertEqual(ass_alpha(1.0), "&H00&")     # opaque
        self.assertEqual(ass_alpha(0.0), "&HFF&")     # invisible

    def test_bad_colour_raises(self):
        with self.assertRaises(ValueError):
            ass_colour("#12345")


class TestTimestamps(unittest.TestCase):
    def test_format(self):
        self.assertEqual(ass_time(0), "0:00:00.00")
        self.assertEqual(ass_time(3.125), "0:00:03.12")   # centiseconds
        self.assertEqual(ass_time(3661.5), "1:01:01.50")

    def test_never_negative(self):
        self.assertEqual(ass_time(-5), "0:00:00.00")


# ---------------------------------------------------------------------------


class TestGrouping(unittest.TestCase):
    def test_max_words(self):
        words = [w(f"w{i}", i * 0.2, i * 0.2 + 0.15) for i in range(12)]
        caps = group_words(words, Layout(max_words_per_card=5))
        self.assertTrue(all(len(c.words) <= 5 for c in caps))

    def test_breaks_on_pause(self):
        words = [w("one", 0.0, 0.3), w("two", 0.35, 0.6),
                 w("three", 2.0, 2.3)]        # 1.4 s gap
        caps = group_words(words, Layout(pause_break=0.45))
        self.assertEqual(len(caps), 2)
        self.assertEqual([x.text for x in caps[0].words], ["one", "two"])

    def test_breaks_after_sentence_end(self):
        words = [w("done.", 0.0, 0.3), w("next", 0.35, 0.6)]
        caps = group_words(words, Layout())
        self.assertEqual(len(caps), 2)

    def test_no_single_short_word_orphan(self):
        words = [w("this", 0.0, 0.2), w("is", 0.2, 0.4), w("a", 0.4, 0.5),
                 w("test", 0.5, 0.8), w("ok", 0.8, 1.0)]
        caps = group_words(words, Layout(max_words_per_card=4))
        self.assertTrue(all(len(c.words) > 1 for c in caps), [c.text for c in caps])

    def test_min_on_screen_time(self):
        caps = group_words([w("hi", 0.0, 0.1)], Layout(min_on_screen=0.7))
        self.assertGreaterEqual(caps[0].end - caps[0].start, 0.7 - 1e-9)

    def test_cards_never_overlap(self):
        words = [w(f"word{i}.", i * 0.5, i * 0.5 + 0.4) for i in range(20)]
        caps = group_words(words, Layout(), duration=20.0)
        for a, b in zip(caps, caps[1:]):
            self.assertLessEqual(a.end, b.start, f"{a.text!r} overruns {b.text!r}")

    def test_cards_never_overlap_when_speech_is_continuous(self):
        """The hard case: no gap between words, so a's +tail collides with b's
        -lead_in. Trimming only `a` leaves them overlapping by lead_in seconds."""
        words = [w(f"word{i}", i * 0.4, (i + 1) * 0.4) for i in range(20)]
        caps = group_words(words, Layout(), duration=20.0)
        for a, b in zip(caps, caps[1:]):
            self.assertLessEqual(a.end, b.start,
                                 f"{a.text!r} overlaps {b.text!r} by {a.end - b.start:.3f}s")

    def test_no_card_is_cut_before_its_last_word_finishes(self):
        words = [w(f"word{i}", i * 0.4, (i + 1) * 0.4) for i in range(20)]
        for cap in group_words(words, Layout(), duration=20.0):
            self.assertGreaterEqual(cap.end, cap.words[-1].end - 1e-9)
            self.assertLessEqual(cap.start, cap.words[0].start + 1e-9)

    def test_never_runs_past_the_video(self):
        caps = group_words([w("end", 9.6, 9.9)], Layout(), duration=10.0)
        self.assertLessEqual(caps[-1].end, 10.0)

    def test_lines_are_balanced_not_ragged(self):
        words = [w("aaaa", i * 0.2, i * 0.2 + 0.1) for i in range(6)]
        cfg = Layout(max_chars_per_line=12, max_words_per_card=6)
        cuts = balance_lines(words, cfg)
        self.assertEqual(len(cuts), 1)
        first, second = cuts[0], len(words) - cuts[0]
        self.assertLessEqual(abs(first - second), 1)      # even, not 5+1

    def test_vertical_gets_narrower_lines(self):
        self.assertEqual(Layout.for_video(1080, 1920).max_chars_per_line, 24)
        self.assertEqual(Layout.for_video(1920, 1080).max_chars_per_line, 42)

    def test_empty_input(self):
        self.assertEqual(group_words([]), [])


# ---------------------------------------------------------------------------


class TestRevealModes(unittest.TestCase):
    """§4.3 — exact event count and time bounds for a known 3-word card."""

    def setUp(self):
        self.card = three_word_card()
        self.theme = Theme()
        self.anim = Animation()
        self.pos = r"{\an2\pos(960,950)}"

    def render(self, mode):
        return render_card(self.card, self.theme, self.anim, self.pos, mode)

    def test_karaoke_event_count(self):
        # 3 words + lead-in (0.95 -> 1.0) + tail (2.1 -> 2.25)
        self.assertEqual(len(self.render("KARAOKE_HIGHLIGHT")), 5)

    def test_word_pop_event_count(self):
        # 3 words + tail; no lead-in because nothing is visible yet
        self.assertEqual(len(self.render("WORD_POP")), 4)

    def test_one_word_event_count(self):
        self.assertEqual(len(self.render("ONE_WORD")), 4)

    def test_typewriter_event_count(self):
        self.assertEqual(len(self.render("TYPEWRITER")), 4)

    def test_whole_card_event_count(self):
        events = self.render("WHOLE_CARD")
        self.assertEqual(len(events), 1)
        self.assertAlmostEqual(events[0].start, 0.95)
        self.assertAlmostEqual(events[0].end, 2.25)

    def test_every_mode_covers_the_card_with_no_gaps(self):
        for mode in MODES:
            with self.subTest(mode=mode):
                events = sorted(self.render(mode), key=lambda e: e.start)
                self.assertAlmostEqual(events[-1].end, self.card.end, places=4)
                for a, b in zip(events, events[1:]):
                    self.assertAlmostEqual(a.end, b.start, places=4,
                                           msg="card would blink out here")

    def test_spans_hold_through_a_mid_card_pause(self):
        card = Caption(
            words=[w("a", 0.0, 0.2), w("b", 1.5, 1.7)], start=0.0, end=1.9
        )
        spans = word_spans(card)
        self.assertAlmostEqual(spans[0][1], 1.5)   # first word held until the second

    def test_word_pop_locks_layout_with_transparency(self):
        """The whole point of Mode B: future words are transparent, NOT omitted."""
        events = self.render("WORD_POP")
        for ev in events[:-1]:
            for word in ("we", "absolutely", "did"):
                self.assertIn(word, ev.text, "a future word was omitted -> jitter")
        self.assertIn(r"\alpha&HFF&", events[0].text)
        self.assertNotIn(r"\alpha&HFF&", events[-1].text)   # tail: all revealed

    def test_separator_space_is_reset_not_inherited(self):
        """A popped word must not widen the space after it (§4.3)."""
        for mode in ("KARAOKE_HIGHLIGHT", "WORD_POP", "TYPEWRITER"):
            with self.subTest(mode=mode):
                for ev in self.render(mode):
                    self.assertNotIn(r"\fscx100} ", ev.text)
                    if " " in ev.text:
                        self.assertIn(r"{\r} ", ev.text)

    def test_one_word_shows_only_one_word(self):
        for ev in self.render("ONE_WORD"):
            present = [x for x in ("we", "absolutely", "did") if x in ev.text]
            self.assertEqual(len(present), 1, ev.text)

    def test_unknown_mode_raises(self):
        with self.assertRaises(ValueError):
            self.render("SPARKLES")

    def test_empty_card_is_safe(self):
        empty = Caption(words=[], start=0.0, end=1.0)
        self.assertEqual(render_card(empty, self.theme, self.anim, self.pos), [])


# ---------------------------------------------------------------------------


class TestAssWriter(unittest.TestCase):
    def setUp(self):
        self.caps = [three_word_card()]

    def test_playres_matches_output_resolution(self):
        doc = build_ass(self.caps, Theme(), res_x=3840, res_y=2160)
        self.assertIn("PlayResX: 3840", doc)
        self.assertIn("PlayResY: 2160", doc)
        self.assertIn("ScaledBorderAndShadow: yes", doc)
        self.assertIn("WrapStyle: 2", doc)

    def test_shadow_colour_is_opaque_by_default(self):
        """BackColour is the SHADOW colour. At alpha FF the shadow vanishes and
        every outlined theme quietly loses its depth."""
        style = Theme().style_line(1920, 1080)
        back = style.split(",")[6]
        self.assertFalse(back.upper().startswith("&HFF"), style)

    def test_boxed_theme_plate_is_translucent(self):
        style = Theme.load("boxed").style_line(1920, 1080)
        self.assertEqual(style.split(",")[15], "3")            # BorderStyle
        self.assertTrue(style.split(",")[5].upper().startswith("&H47"))   # ~72% opaque

    def test_sizes_scale_with_resolution(self):
        theme = Theme(size_pct=8.0)
        self.assertEqual(theme.font_size(1080), 86)
        self.assertEqual(theme.font_size(2160), 173)      # identical look at 4K

    def test_all_fifty_combinations_render(self):
        """10 themes x 5 reveal modes, none crashing, none empty (§10)."""
        for name in Theme.available():
            theme = Theme.load(name)
            for mode in MODES:
                with self.subTest(theme=name, mode=mode):
                    doc = build_ass(self.caps, theme, Animation(), mode, 1920, 1080)
                    self.assertIn("[Events]", doc)
                    self.assertGreaterEqual(doc.count("Dialogue:"), 1)
                    self.assertNotIn("Dialogue: 0,0:00:00.00,0:00:00.00", doc)

    def test_gradient_theme_stacks_two_layers(self):
        theme = Theme.load("gradient_pop")
        doc = build_ass(self.caps, theme, Animation(), "WORD_POP", 1920, 1080)
        self.assertIn("Dialogue: 1,", doc)      # overlay copy
        self.assertIn(r"\clip(", doc)

    def test_gradient_overlay_has_its_own_style(self):
        """Reveal modes mark inactive words with a bare \\r, which resets to the
        EVENT's style. If the overlay shared 'Sub' it would be redrawn in the base
        colour and the gradient would render as flat single-colour text."""
        theme = Theme.load("gradient_pop")
        doc = build_ass(self.caps, theme, Animation(), "WORD_POP", 1920, 1080)
        self.assertIn("Style: SubGrad0,", doc)
        overlay = [ln for ln in doc.splitlines() if ln.startswith("Dialogue: 1,")]
        self.assertTrue(overlay)
        for line in overlay:
            self.assertEqual(line.split(",")[3], "SubGrad0")
        base = ass_style_colour(theme.base_colour)
        grad = ass_style_colour(theme.gradient_colour)
        self.assertNotEqual(base, grad)
        style_lines = [ln for ln in doc.splitlines() if ln.startswith("Style: SubGrad")]
        self.assertIn(grad, style_lines[0])

    def test_non_gradient_themes_emit_only_one_style(self):
        doc = build_ass(self.caps, Theme.load("hormozi"), Animation(),
                        "WORD_POP", 1920, 1080)
        self.assertEqual(doc.count("\nStyle: "), 1)

    def test_one_word_mode_centres_and_enlarges(self):
        theme = Theme.load("hormozi")
        doc = build_ass(self.caps, theme, Animation(), "ONE_WORD", 1920, 1080)
        self.assertIn(r"\an5", doc)

    def test_no_literal_newlines_inside_an_event(self):
        doc = build_ass(self.caps, Theme.load("hormozi"), Animation(),
                        "KARAOKE_HIGHLIGHT", 1920, 1080)
        for line in doc.splitlines():
            if line.startswith("Dialogue:"):
                self.assertEqual(line.count("Dialogue:"), 1)

    def test_theme_and_mode_are_independent(self):
        """Every theme must work in every mode — no theme x mode branching (§4.4)."""
        docs = {
            (n, m): build_ass(self.caps, Theme.load(n), Animation(), m, 1920, 1080)
            for n in Theme.available() for m in MODES
        }
        self.assertEqual(len(docs), len(Theme.available()) * len(MODES))


class TestLayers(unittest.TestCase):
    """Stacked caption tracks — each with its own words, look and motion."""

    def _layer(self, **kw):
        words = [w("one", 0.0, 0.4), w("two", 0.4, 0.8), w("three", 0.8, 1.2)]
        base = dict(name="L", theme="hormozi", reveal="WORD_POP", words=words)
        base.update(kw)
        return Layer(**base)

    def test_layer_resolves_theme_overrides(self):
        layer = self._layer(size_pct=13.0, margin_v_pct=30.0, position="top")
        theme = core_layers.resolve_theme(layer)
        self.assertEqual(theme.size_pct, 13.0)
        self.assertEqual(theme.margin_v_pct, 30.0)
        self.assertEqual(theme.position, "top")

    def test_layer_without_overrides_uses_the_theme(self):
        theme = core_layers.resolve_theme(self._layer())
        self.assertEqual(theme.size_pct, Theme.load("hormozi").size_pct)

    def test_one_word_and_one_phrase_cap_the_card_size(self):
        cfg = core_layers.layout_for(self._layer(reveal="ONE_WORD", max_words=8),
                                     1920, 1080)
        self.assertEqual(cfg.max_words_per_card, 1)
        cfg = core_layers.layout_for(self._layer(reveal="ONE_PHRASE", max_words=8),
                                     1920, 1080)
        self.assertEqual(cfg.max_words_per_card, 3)

    def test_time_offset_shifts_a_layer(self):
        layer = self._layer(time_offset=0.5)
        caps = core_layers.layer_captions(layer, 1920, 1080, 10.0)
        self.assertAlmostEqual(caps[0].words[0].start, 0.5, places=3)
        self.assertEqual(layer.words[0].start, 0.0, "source words must not move")

    def test_two_layers_get_separate_styles_and_z_order(self):
        a = self._layer(name="A", theme="hormozi", reveal="WORD_POP")
        b = self._layer(name="B", theme="neon", reveal="ONE_WORD", position="top")
        doc = core_layers.build_ass_for([a, b], 1920, 1080, 10.0)
        self.assertIn("Style: Sub0,", doc)
        self.assertIn("Style: Sub1,", doc)
        styles = {ln.split(",")[3] for ln in doc.splitlines()
                  if ln.startswith("Dialogue:")}
        self.assertEqual(styles, {"Sub0", "Sub1"})
        layer_nums = {int(ln.split(":")[1].split(",")[0])
                      for ln in doc.splitlines() if ln.startswith("Dialogue:")}
        self.assertEqual(layer_nums, {0, 10}, "later layers must draw on top")

    def test_disabled_layer_is_not_rendered(self):
        a = self._layer(name="A")
        b = self._layer(name="B", theme="neon", enabled=False)
        doc = core_layers.build_ass_for([a, b], 1920, 1080, 10.0)
        self.assertIn("Style: Sub0,", doc)
        self.assertNotIn("Style: Sub1,", doc)

    def test_empty_layer_contributes_nothing(self):
        doc = core_layers.build_ass_for([self._layer(), self._layer(words=[])],
                                        1920, 1080, 10.0)
        self.assertNotIn("Sub1", doc)

    def test_project_round_trips_layers(self):
        import tempfile

        layers = [self._layer(name="A"),
                  self._layer(name="B", theme="neon", reveal="ONE_PHRASE",
                              enabled=False, size_pct=9.0, position="top",
                              time_offset=0.25)]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "p.subproj"
            Project(source="v.mp4", layers=layers, language="en").save(path)
            loaded = Project.load(path)
        self.assertEqual([l.name for l in loaded.layers], ["A", "B"])
        self.assertFalse(loaded.layers[1].enabled)
        self.assertEqual(loaded.layers[1].reveal, "ONE_PHRASE")
        self.assertAlmostEqual(loaded.layers[1].time_offset, 0.25)
        self.assertEqual(len(loaded.layers[0].words), 3)

    def test_old_single_layer_project_still_opens(self):
        """Schema 1 stored a flat caption list — it must fold into one layer."""
        import json
        import tempfile

        legacy = {
            "source": "v.mp4", "schema": 1, "theme": "neon", "reveal": "TYPEWRITER",
            "language": "en",
            "captions": [{"words": [{"text": "hi", "start": 0.0, "end": 0.3,
                                     "prob": 1.0}],
                          "start": 0.0, "end": 0.5, "line_starts": [],
                          "style": "default"}],
        }
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "old.subproj"
            path.write_text(json.dumps(legacy), encoding="utf-8")
            loaded = Project.load(path)
        self.assertEqual(len(loaded.layers), 1)
        self.assertEqual(loaded.layers[0].theme, "neon")
        self.assertEqual(loaded.layers[0].reveal, "TYPEWRITER")
        self.assertEqual(loaded.layers[0].words[0].text, "hi")


class TestOnePhrase(unittest.TestCase):
    def setUp(self):
        self.card = three_word_card()

    def test_event_count(self):
        events = render_card(self.card, Theme(), Animation(), "{}", "ONE_PHRASE")
        self.assertEqual(len(events), 4)          # 3 words + tail

    def test_shows_a_window_of_words_not_all(self):
        words = [w(f"w{i}", i * 0.3, (i + 1) * 0.3) for i in range(5)]
        card = Caption(words=words, start=0.0, end=1.5)
        states = word_state(card, 0.05, "ONE_PHRASE")
        self.assertEqual(states[0], "active")
        self.assertEqual(states[1], "base")
        self.assertEqual(states[3], "hidden", "far words must stay hidden")

    def test_middle_of_card_keeps_neighbours_visible(self):
        words = [w(f"w{i}", i * 0.3, (i + 1) * 0.3) for i in range(5)]
        card = Caption(words=words, start=0.0, end=1.5)
        states = word_state(card, 0.65, "ONE_PHRASE")     # word 2 active
        self.assertEqual(states[2], "active")
        self.assertEqual(states[1], "base")
        self.assertEqual(states[3], "base")
        self.assertEqual(states[0], "hidden")


class TestPopAnimation(unittest.TestCase):
    """The pop must be a visible grow-and-settle, not a 12% twitch."""

    def test_starts_small_overshoots_then_settles(self):
        a = Animation()
        self.assertLess(a.scale_at(0), 0.8, "should spring up from small")
        self.assertGreater(a.scale_at(a.pop_rise_ms), 1.05, "should overshoot")
        self.assertAlmostEqual(a.scale_at(a.pop_ms), 1.0, places=3)
        self.assertAlmostEqual(a.scale_at(a.pop_ms * 5), 1.0, places=3)

    def test_size_change_is_perceptible_but_not_violent(self):
        a = Animation()
        smallest = min(a.scale_at(ms) for ms in range(0, a.pop_ms, 5))
        biggest = max(a.scale_at(ms) for ms in range(0, a.pop_ms, 5))
        travel = biggest - smallest
        self.assertGreater(travel, 0.20, "under 20% reads as a twitch")
        self.assertLess(travel, 0.45, "over 45% is distracting by default")

    def test_ass_tags_describe_the_same_two_stage_move(self):
        a = Animation()
        tags = Theme().active_tags(0, animate=True, anim=a)
        self.assertIn(rf"\fscx{a.pop_from}", tags)                     # springs up
        self.assertIn(rf"\t(0,{a.pop_rise_ms},\fscx{a.pop_over}", tags)  # overshoot
        self.assertIn(rf"\t({a.pop_rise_ms},{a.pop_ms},\fscx100", tags)  # settle

    def test_ass_and_qt_agree_on_the_curve(self):
        """Both paths must read the same numbers, or preview and export differ."""
        a = Animation()
        self.assertAlmostEqual(a.scale_at(0), a.pop_from / 100)
        self.assertAlmostEqual(a.scale_at(a.pop_rise_ms), a.pop_over / 100)

    def test_presets_span_still_to_exaggerated(self):
        still = core_layers.animation_for(
            Layer(**dict(zip(("pop_strength", "pop_speed", "tilt_deg",
                              "word_fade_ms"), core_layers.EFFECT_PRESETS["None"]))))
        big = core_layers.animation_for(
            Layer(**dict(zip(("pop_strength", "pop_speed", "tilt_deg",
                              "word_fade_ms"), core_layers.EFFECT_PRESETS["Big"]))))
        self.assertFalse(still.moves)
        self.assertLess(big.pop_from, Animation().pop_from)
        self.assertGreater(big.pop_over, Animation().pop_over)

    def test_pop_speed_shortens_the_animation(self):
        fast = core_layers.animation_for(Layer(pop_speed=2.0))
        slow = core_layers.animation_for(Layer(pop_speed=0.5))
        self.assertLess(fast.pop_ms, Animation().pop_ms)
        self.assertGreater(slow.pop_ms, Animation().pop_ms)

    def test_tilt_unwinds_to_zero(self):
        a = core_layers.animation_for(Layer(tilt_deg=8.0))
        self.assertAlmostEqual(a.tilt_at(0), 8.0, places=3)
        self.assertAlmostEqual(a.tilt_at(a.pop_ms), 0.0, places=3)
        self.assertIn(r"\frz8", Theme().active_tags(0, animate=True, anim=a))

    def test_word_fade_reaches_full_opacity(self):
        a = core_layers.animation_for(Layer(word_fade_ms=100))
        self.assertAlmostEqual(a.word_alpha_at(0), 0.0)
        self.assertAlmostEqual(a.word_alpha_at(50), 0.5)
        self.assertAlmostEqual(a.word_alpha_at(200), 1.0)
        self.assertIn(r"\alpha&HFF&", Theme().active_tags(0, animate=True, anim=a))

    def test_pop_strength_scales_it(self):
        strong = core_layers.animation_for(Layer(pop_strength=2.0))
        self.assertLess(strong.pop_from, Animation().pop_from)
        self.assertGreater(strong.pop_over, Animation().pop_over)

    def test_zero_pop_strength_means_no_movement(self):
        still = core_layers.animation_for(Layer(pop_strength=0.0))
        self.assertEqual(still.pop_from, 100)
        self.assertEqual(still.pop_over, 100)
        self.assertAlmostEqual(still.scale_at(0), 1.0)
        tags = Theme().active_tags(0, animate=True, anim=still)
        self.assertNotIn(r"\t(", tags, "no animation tags when strength is 0")


class TestLineSpacing(unittest.TestCase):
    def test_spacing_theme_emits_a_spacer_line(self):
        plain = Theme(line_spacing_pct=0)
        spaced = Theme(line_spacing_pct=30)
        self.assertEqual(plain.line_break_tag(), r"\N")
        self.assertIn(r"\N", spaced.line_break_tag())
        self.assertIn(r"\fscy30", spaced.line_break_tag())

    def test_spacing_reaches_the_document(self):
        words = [w("aaaa", i * 0.2, i * 0.2 + 0.15) for i in range(6)]
        caps = group_words(words, Layout(max_chars_per_line=10,
                                         max_words_per_card=6))
        theme = Theme(line_spacing_pct=25)
        doc = build_ass(caps, theme, Animation(), "WHOLE_CARD", 1920, 1080)
        self.assertIn(r"\fscy25", doc)


class TestSrt(unittest.TestCase):
    def test_srt_shape(self):
        out = build_srt([three_word_card()])
        self.assertIn("00:00:00,950 --> 00:00:02,250", out)
        self.assertIn("we absolutely did", out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
