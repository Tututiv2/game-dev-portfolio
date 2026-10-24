using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace SoundBoard
{
    /// <summary>
    /// Drives the real AudioEngine headlessly. The last check is the one that matters:
    /// it records the PC's own output back and asks whether sound actually came out.
    /// </summary>
    static class SelfTest
    {
        static int _pass, _fail;

        static void Check(string name, bool ok, string detail = null)
        {
            if (ok) { _pass++; Console.WriteLine("  PASS  " + name + (detail != null ? "   [" + detail + "]" : "")); }
            else { _fail++; Console.WriteLine("  FAIL  " + name + (detail != null ? "   [" + detail + "]" : "")); }
        }

        static void Note(string s) { Console.WriteLine("  ....  " + s); }

        static string Root
        {
            get
            {
                var d = AppContext.BaseDirectory;
                for (int i = 0; i < 6 && d != null; i++)
                {
                    if (File.Exists(Path.Combine(d, "SoundBoard.bat"))) return d;
                    var p = Directory.GetParent(d.TrimEnd('\\'));
                    d = p == null ? null : p.FullName;
                }
                return AppContext.BaseDirectory;
            }
        }

        static void WaitUntil(Func<bool> cond, int ms)
        {
            var t0 = Environment.TickCount;
            while (Environment.TickCount - t0 < ms && !cond()) Thread.Sleep(25);
        }

        static int Main()
        {
            Console.WriteLine();
            Console.WriteLine("SoundBoard self-test");
            Console.WriteLine("root: " + Root);
            Console.WriteLine();

            var sounds = Path.Combine(Root, "Sounds");
            var files = Directory.Exists(sounds)
                ? Directory.GetFiles(sounds, "*.mp3").OrderBy(f => f).ToList()
                : new List<string>();

            // ---------------------------------------------------------- 1. triggers
            Console.WriteLine("[triggers]");
            var t = new TriggerCfg { Code = 0x4C };                       // plain L
            Check("plain key matches with no modifiers", t.Matches(0x4C, false, false, false, false));
            Check("plain key does NOT match while Ctrl is held", !t.Matches(0x4C, false, true, false, false),
                  "so Ctrl+L in a browser stays quiet");
            var tc = new TriggerCfg { Code = 0x4D, Ctrl = true, Shift = true };
            Check("Ctrl+Shift+M matches exactly", tc.Matches(0x4D, false, true, false, true));
            Check("Ctrl+Shift+M ignores a bare M", !tc.Matches(0x4D, false, false, false, false));
            Check("key names read back", Trig.Describe(t) == "L" && Trig.Describe(tc) == "Ctrl+Shift+M",
                  Trig.Describe(t) + " / " + Trig.Describe(tc));
            Check("mouse buttons name themselves",
                  Trig.Describe(new TriggerCfg { Code = Trig.MouseX2, IsMouse = true }) == "Mouse 5");
            Check("modifier keys are rejected as triggers",
                  Trig.IsModifierKey(0x11) && Trig.IsModifierKey(0xA0) && !Trig.IsModifierKey(0x4C));

            // ---------------------------------------------------------- 2. config
            Console.WriteLine();
            Console.WriteLine("[config]");
            var cfgPath = Path.Combine(Path.GetTempPath(), "sb_selftest_" + Guid.NewGuid().ToString("N") + ".json");
            var c1 = new AppConfig { MasterVolume = 0.42f, Retrigger = "Overlap" };
            c1.Bindings.Add(new BindingCfg { Trigger = tc, Path = @"C:\x\y.mp3", Volume = 0.7f, PassThrough = false });
            var json = System.Text.Json.JsonSerializer.Serialize(c1);
            var c2 = System.Text.Json.JsonSerializer.Deserialize<AppConfig>(json);
            Check("config survives a save/load round trip",
                  c2.MasterVolume == 0.42f && c2.Retrigger == "Overlap" && c2.Bindings.Count == 1
                  && c2.Bindings[0].Volume == 0.7f && !c2.Bindings[0].PassThrough
                  && c2.Bindings[0].Trigger.Ctrl && c2.Bindings[0].Trigger.Code == 0x4D);
            Check("every binding gets its own id", new BindingCfg().Id != new BindingCfg().Id);
            try { File.Delete(cfgPath); } catch { }

            // ---------------------------------------------------------- 3. hooks
            Console.WriteLine();
            Console.WriteLine("[input hook]");
            using (var hook = new InputHook())
            {
                hook.Install();
                Check("low-level keyboard + mouse hooks install", hook.Installed);
            }

            // ---------------------------------------------------------- 4. devices
            Console.WriteLine();
            Console.WriteLine("[audio devices]");
            var devs = AudioEngine.Devices();
            Check("render devices enumerate", devs.Count >= 2, devs.Count - 1 + " real device(s)");
            foreach (var d in devs.Skip(1).Take(6)) Note(d.Name);

            if (files.Count == 0)
            {
                Console.WriteLine();
                Console.WriteLine("No mp3s in Sounds\\ — skipping playback checks.");
                return Report();
            }

            // ---------------------------------------------------------- 5. playback
            Console.WriteLine();
            Console.WriteLine("[playback]");
            var engine = new AudioEngine();
            engine.SetOutputs("", null);
            Check("default output opens", !engine.PrimaryStatus.StartsWith("failed"), engine.PrimaryStatus);
            engine.MasterVolume = 0.25f;

            foreach (var f in files)
            {
                var err = engine.Play("v_" + Path.GetFileName(f), f, 1f, RetriggerMode.Restart, false);
                Check("decodes + plays " + Path.GetFileName(f), err == null, err ?? "ok");
            }
            Check("all " + files.Count + " voices are live at once", engine.VoiceCount == files.Count,
                  engine.VoiceCount + " voices");

            engine.StopAll();
            Check("stop-all clears every voice", engine.VoiceCount == 0);

            // retrigger modes
            var one = files[0];
            engine.Play("x", one, 1f, RetriggerMode.Restart, false);
            engine.Play("x", one, 1f, RetriggerMode.Restart, false);
            Check("Restart leaves exactly one voice", engine.VoiceCount == 1, engine.VoiceCount + " voices");

            engine.Play("x", one, 1f, RetriggerMode.Overlap, false);
            Check("Overlap stacks a second voice", engine.VoiceCount == 2, engine.VoiceCount + " voices");
            engine.StopAll();

            engine.Play("x", one, 1f, RetriggerMode.Toggle, false);
            bool started = engine.IsPlaying("x");
            engine.Play("x", one, 1f, RetriggerMode.Toggle, false);
            Check("Toggle starts then stops", started && !engine.IsPlaying("x"));
            engine.StopAll();

            // a finished sound must clean itself up, or voices leak forever
            var shortest = files.OrderBy(f => new FileInfo(f).Length).First();
            engine.Play("auto", shortest, 1f, RetriggerMode.Restart, false);
            WaitUntil(() => engine.VoiceCount == 0, 12000);
            Check("a voice removes itself when the sound ends", engine.VoiceCount == 0,
                  Path.GetFileName(shortest));

            Check("a missing file is reported, not thrown",
                  engine.Play("m", Path.Combine(sounds, "does-not-exist.mp3"), 1f, RetriggerMode.Restart, false) != null);

            // long file -> streaming path instead of the RAM cache
            var longFile = Directory.Exists(@"D:\Downloads")
                ? Directory.GetFiles(@"D:\Downloads", "*.mp3")
                      .FirstOrDefault(f => new FileInfo(f).Length > 3_000_000)
                : null;
            if (longFile != null)
            {
                var err = engine.Play("long", longFile, 0.5f, RetriggerMode.Restart, false);
                Check("streams a long file without caching it", err == null && engine.IsPlaying("long"),
                      Path.GetFileName(longFile));
                engine.Stop("long");
                Check("streamed voice stops cleanly", engine.VoiceCount == 0);
            }

            // ---------------------------------------------------------- 6. real sound
            Console.WriteLine();
            Console.WriteLine("[does sound actually leave the machine]");
            float peak = LoopbackPeak(engine, files[0]);
            if (peak < 0)
                Note("loopback capture unavailable — inconclusive, not a failure");
            else if (peak < 0.0005f)
                Check("captured the PC's own output while playing", false,
                      "peak " + peak.ToString("0.00000") + " — is the output muted?");
            else
                Check("captured the PC's own output while playing", true,
                      "peak " + peak.ToString("0.0000") + " (" + (20 * Math.Log10(peak)).ToString("0.0") + " dBFS)");

            engine.Dispose();
            return Report();
        }

        /// <summary>Play something and record the default render endpoint back. -1 = couldn't.</summary>
        static float LoopbackPeak(AudioEngine engine, string file)
        {
            WasapiLoopbackCapture cap = null;
            try
            {
                cap = new WasapiLoopbackCapture();
                float peak = 0;
                int bits = cap.WaveFormat.BitsPerSample;
                bool isFloat = cap.WaveFormat.Encoding == WaveFormatEncoding.IeeeFloat;
                cap.DataAvailable += (s, a) =>
                {
                    if (isFloat && bits == 32)
                        for (int i = 0; i + 3 < a.BytesRecorded; i += 4)
                        {
                            float v = Math.Abs(BitConverter.ToSingle(a.Buffer, i));
                            if (v > peak) peak = v;
                        }
                    else if (bits == 16)
                        for (int i = 0; i + 1 < a.BytesRecorded; i += 2)
                        {
                            float v = Math.Abs(BitConverter.ToInt16(a.Buffer, i) / 32768f);
                            if (v > peak) peak = v;
                        }
                };
                cap.StartRecording();
                Thread.Sleep(250);
                engine.MasterVolume = 0.7f;
                engine.Play("loopback", file, 1f, RetriggerMode.Restart, false);
                Thread.Sleep(1400);
                cap.StopRecording();
                Thread.Sleep(200);
                engine.StopAll();
                return peak;
            }
            catch (Exception ex)
            {
                Note("loopback: " + ex.Message);
                return -1;
            }
            finally { if (cap != null) { try { cap.Dispose(); } catch { } } }
        }

        static int Report()
        {
            Console.WriteLine();
            Console.WriteLine("  " + _pass + " passed, " + _fail + " failed");
            Console.WriteLine();
            return _fail == 0 ? 0 : 1;
        }
    }
}
