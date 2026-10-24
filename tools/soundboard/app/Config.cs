using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Windows.Input;

namespace SoundBoard
{
    /// <summary>A key or mouse button, plus the modifier state required to match it.</summary>
    public class TriggerCfg
    {
        public int Code { get; set; }          // virtual-key code, or one of Trig.Mouse* pseudo codes
        public bool IsMouse { get; set; }
        public bool Ctrl { get; set; }
        public bool Alt { get; set; }
        public bool Shift { get; set; }

        public bool Matches(int code, bool isMouse, bool ctrl, bool alt, bool shift)
            => Code == code && IsMouse == isMouse && Ctrl == ctrl && Alt == alt && Shift == shift;

        public TriggerCfg Clone() => new TriggerCfg
        { Code = Code, IsMouse = IsMouse, Ctrl = Ctrl, Alt = Alt, Shift = Shift };

        public override string ToString() => Trig.Describe(this);
    }

    public static class Trig
    {
        // Pseudo codes for mouse buttons. Left/right are deliberately not bindable —
        // swallowing them would make the machine unusable.
        public const int MouseMiddle = 0x2001;
        public const int MouseX1 = 0x2002;   // "Mouse 4"
        public const int MouseX2 = 0x2003;   // "Mouse 5"

        public static string Describe(TriggerCfg t)
        {
            if (t == null || t.Code == 0) return "—";
            string s = "";
            if (t.Ctrl) s += "Ctrl+";
            if (t.Alt) s += "Alt+";
            if (t.Shift) s += "Shift+";
            return s + Name(t.Code, t.IsMouse);
        }

        public static string Name(int code, bool isMouse)
        {
            if (isMouse)
            {
                switch (code)
                {
                    case MouseMiddle: return "Mouse 3";
                    case MouseX1: return "Mouse 4";
                    case MouseX2: return "Mouse 5";
                    default: return "Mouse ?";
                }
            }
            switch (code)
            {
                case 0xBA: return ";";
                case 0xBB: return "=";
                case 0xBC: return ",";
                case 0xBD: return "-";
                case 0xBE: return ".";
                case 0xBF: return "/";
                case 0xC0: return "`";
                case 0xDB: return "[";
                case 0xDC: return "\\";
                case 0xDD: return "]";
                case 0xDE: return "'";
                case 0x20: return "Space";
            }
            try
            {
                var k = KeyInterop.KeyFromVirtualKey(code);
                if (k == Key.None) return "VK" + code;
                var n = k.ToString();
                if (n.StartsWith("D") && n.Length == 2 && char.IsDigit(n[1])) return n.Substring(1);
                if (n.StartsWith("NumPad")) return "Num" + n.Substring(6);
                return n;
            }
            catch { return "VK" + code; }
        }

        /// <summary>Modifier keys can't themselves be a trigger — they'd fire constantly.</summary>
        public static bool IsModifierKey(int vk)
            => vk == 0x10 || vk == 0x11 || vk == 0x12               // Shift / Ctrl / Alt
            || (vk >= 0xA0 && vk <= 0xA5)                            // L/R Shift, Ctrl, Alt
            || vk == 0x5B || vk == 0x5C;                             // Win keys
    }

    public class BindingCfg
    {
        public string Id { get; set; } = Guid.NewGuid().ToString("N");
        public TriggerCfg Trigger { get; set; } = new TriggerCfg();
        public string Path { get; set; } = "";
        public float Volume { get; set; } = 1f;
        /// <summary>Let the key reach the game as well (default). Off = the soundboard eats it.</summary>
        public bool PassThrough { get; set; } = true;
    }

    public class AppConfig
    {
        public bool Enabled { get; set; } = true;
        public float MasterVolume { get; set; } = 0.8f;

        public string PrimaryDeviceId { get; set; } = "";     // "" = system default
        public string SecondaryDeviceId { get; set; } = "";
        public bool SecondaryEnabled { get; set; } = false;

        /// <summary>Restart | Overlap | Toggle</summary>
        public string Retrigger { get; set; } = "Restart";

        public bool AlwaysOnTop { get; set; } = false;
        /// <summary>Off by default: a window that vanishes into a hidden tray icon reads as lost.</summary>
        public bool MinimizeToTray { get; set; } = false;

        public TriggerCfg ToggleHotkey { get; set; } = new TriggerCfg { Code = 0x4D, Ctrl = true, Shift = true }; // Ctrl+Shift+M
        public TriggerCfg StopHotkey { get; set; } = new TriggerCfg { Code = 0x58, Ctrl = true, Shift = true };   // Ctrl+Shift+X

        public List<string> LibraryFolders { get; set; } = new List<string>();
        public List<BindingCfg> Bindings { get; set; } = new List<BindingCfg>();

        /// <summary>
        /// Config lives beside SoundBoard.bat (the project root), not in bin\ — so
        /// deleting the build output never costs you your bindings.
        /// </summary>
        [JsonIgnore]
        public static string Dir
        {
            get
            {
                var d = AppContext.BaseDirectory.TrimEnd('\\');
                try
                {
                    var parent = Directory.GetParent(d);
                    if (parent != null && File.Exists(Path.Combine(parent.FullName, "SoundBoard.bat")))
                        return parent.FullName;
                }
                catch { }
                return d;
            }
        }

        [JsonIgnore]
        public static string FilePath => Path.Combine(Dir, "config.json");

        static readonly JsonSerializerOptions Opts = new JsonSerializerOptions
        {
            WriteIndented = true,
            DefaultIgnoreCondition = JsonIgnoreCondition.Never
        };

        public static AppConfig Load()
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    var cfg = JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(FilePath), Opts);
                    if (cfg != null) return cfg;
                }
            }
            catch { /* a corrupt config must never stop the app opening */ }
            return null;
        }

        /// <summary>First run: bind the starter pack if those files are sitting in Sounds\.</summary>
        public static AppConfig CreateDefault()
        {
            var cfg = new AppConfig();
            var sounds = Path.Combine(Dir, "Sounds");
            cfg.LibraryFolders.Add(sounds);
            if (Directory.Exists(@"D:\Downloads")) cfg.LibraryFolders.Add(@"D:\Downloads");

            var starter = new (int Vk, string File)[]
            {
                (0x49, "dragon_ball_z_tele.mp3"),            // I
                (0x55, "ive-got-this.mp3"),                  // U
                (0x4C, "micheal-jackson-rizz.mp3"),          // L
                (0x4A, "rizzbot-laugh.mp3"),                 // J
                (0x4B, "correct.mp3"),                       // K
                (0x4F, "dexter-meme.mp3"),                   // O
                (0x50, "among-us-role-reveal-sound.mp3"),    // P
            };
            foreach (var s in starter)
            {
                var full = Path.Combine(sounds, s.File);
                if (File.Exists(full))
                    cfg.Bindings.Add(new BindingCfg
                    {
                        Trigger = new TriggerCfg { Code = s.Vk },
                        Path = full
                    });
            }
            return cfg;
        }

        public void Save()
        {
            try
            {
                var tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(this, Opts));
                File.Copy(tmp, FilePath, true);
                File.Delete(tmp);
            }
            catch { }
        }
    }
}
