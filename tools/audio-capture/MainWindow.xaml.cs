using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Threading;
using Rectangle = System.Windows.Shapes.Rectangle;
using AudioCapture.Recording;
using NAudio.CoreAudioApi;

namespace AudioCapture;

public partial class MainWindow : Window
{
    private enum SourceMode { Mic, System, Both }

    private sealed class DeviceItem
    {
        public string Label = "";
        public string Id = "";
        public bool IsDefault;
        public DataFlow Flow;
        public override string ToString() => Label;

        /// <summary>
        /// A fresh MMDevice every time. NAudio caches one AudioClient per MMDevice and disposes it
        /// with the capture, so a reused instance comes back dead on the second recording.
        /// </summary>
        public MMDevice? Resolve(MMDeviceEnumerator en)
        {
            try
            {
                return IsDefault ? en.GetDefaultAudioEndpoint(Flow, Role.Multimedia) : en.GetDevice(Id);
            }
            catch { return null; }
        }
    }

    private sealed class Settings
    {
        public string Mode { get; set; } = "Mic";
        public string MicId { get; set; } = "";
        public string SysId { get; set; } = "";
        public int Rate { get; set; } = 24000;
        public bool Stereo { get; set; }
        public bool Trim { get; set; } = true;
        public bool Norm { get; set; } = true;
        public string Folder { get; set; } = "";
        public string Prefix { get; set; } = "voice";
        public bool Dataset { get; set; }
        public bool AskAfterTake { get; set; } = true;
        public double MicGain { get; set; } = 1;
        public double SysGain { get; set; } = 1;
    }

    private readonly MMDeviceEnumerator enumerator = new();
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(50) };
    private readonly List<DeviceItem> micItems = new();
    private readonly List<DeviceItem> sysItems = new();

    private Recorder? recorder;
    private LevelMonitor? monitor;
    private bool ready;
    private float micDisp, sysDisp;

    private const int HotkeyId = 0xA17;
    private const uint ModControl = 0x0002, ModShift = 0x0004;
    private const uint VkR = 0x52;
    private const int WmHotkey = 0x0312;
    private bool hotkeyOk;

    [DllImport("user32.dll")] private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint mods, uint vk);
    [DllImport("user32.dll")] private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    public MainWindow()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Closing += OnClosing;
        timer.Tick += Tick;
    }

    // ── setup ────────────────────────────────────────────────────────────

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        foreach (var (label, rate) in new[]
        {
            ("48 000 Hz   —   sound effects, music, anything off screen", 48000),
            ("44 100 Hz   —   CD quality", 44100),
            ("24 000 Hz   —   IndexTTS voice prompt", 24000),
            ("22 050 Hz   —   LJSpeech / fine-tuning datasets", 22050),
            ("16 000 Hz   —   Whisper / ASR transcription", 16000),
        })
        {
            RatePreset.Items.Add(new ComboBoxItem { Content = label, Tag = rate });
        }

        LoadDevices();

        RatePreset.SelectionChanged += (_, _) => UpdateRateHint();
        MicDevice.SelectionChanged += Device_Changed;
        SysDevice.SelectionChanged += Device_Changed;

        ApplySettings(LoadSettings());

        ready = true;
        UpdateRows();
        RestartMonitor();
        timer.Start();
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        var handle = new WindowInteropHelper(this).Handle;
        HwndSource.FromHwnd(handle)?.AddHook(WndProc);
        hotkeyOk = RegisterHotKey(handle, HotkeyId, ModControl | ModShift, VkR);
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WmHotkey && wParam.ToInt32() == HotkeyId)
        {
            handled = true;
            Toggle();
        }
        return IntPtr.Zero;
    }

    private void LoadDevices()
    {
        micItems.Clear();
        sysItems.Clear();
        Fill(micItems, DataFlow.Capture);
        Fill(sysItems, DataFlow.Render);

        MicDevice.ItemsSource = null;
        MicDevice.ItemsSource = micItems;
        SysDevice.ItemsSource = null;
        SysDevice.ItemsSource = sysItems;

        if (micItems.Count > 0) MicDevice.SelectedIndex = 0;
        if (sysItems.Count > 0) SysDevice.SelectedIndex = 0;
    }

    private void Fill(List<DeviceItem> list, DataFlow flow)
    {
        try
        {
            var def = enumerator.GetDefaultAudioEndpoint(flow, Role.Multimedia);
            list.Add(new DeviceItem { Label = "Default  —  " + def.FriendlyName, Id = def.ID, IsDefault = true, Flow = flow });
        }
        catch { /* no default endpoint for this flow */ }

        try
        {
            foreach (var d in enumerator.EnumerateAudioEndPoints(flow, DeviceState.Active))
                list.Add(new DeviceItem { Label = d.FriendlyName, Id = d.ID, Flow = flow });
        }
        catch { }
    }

    private static string DefaultFolder() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "AudioCapture");

    private static string SettingsPath() =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AudioCapture", "settings.json");

    private Settings LoadSettings()
    {
        try
        {
            var p = SettingsPath();
            if (File.Exists(p))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(p)) ?? new Settings();
        }
        catch { }
        return new Settings();
    }

    private void ApplySettings(Settings s)
    {
        ModeMic.IsChecked = s.Mode == "Mic";
        ModeSys.IsChecked = s.Mode == "System";
        ModeBoth.IsChecked = s.Mode == "Both";
        if (ModeMic.IsChecked != true && ModeSys.IsChecked != true && ModeBoth.IsChecked != true)
            ModeMic.IsChecked = true;

        SelectById(MicDevice, micItems, s.MicId);
        SelectById(SysDevice, sysItems, s.SysId);

        RatePreset.SelectedIndex = 0;
        for (int i = 0; i < RatePreset.Items.Count; i++)
        {
            if (RatePreset.Items[i] is ComboBoxItem ci && (int)ci.Tag! == s.Rate) { RatePreset.SelectedIndex = i; break; }
        }

        Stereo.IsChecked = s.Stereo;
        TrimSilence.IsChecked = s.Trim;
        Normalise.IsChecked = s.Norm;
        OutFolder.Text = string.IsNullOrWhiteSpace(s.Folder) ? DefaultFolder() : s.Folder;
        Prefix.Text = string.IsNullOrWhiteSpace(s.Prefix) ? "voice" : s.Prefix;
        DatasetMode.IsChecked = s.Dataset;
        AskAfterTake.IsChecked = s.AskAfterTake;
        MicGain.Value = Math.Clamp(s.MicGain, MicGain.Minimum, MicGain.Maximum);
        SysGain.Value = Math.Clamp(s.SysGain, SysGain.Minimum, SysGain.Maximum);
        Gain_Changed(this, null!);
        Dataset_Changed(this, null!);
        UpdateRateHint();
    }

    private static void SelectById(ComboBox box, List<DeviceItem> items, string id)
    {
        if (string.IsNullOrEmpty(id)) return;
        // Skip index 0: that entry is "whatever is default right now", which should stay dynamic.
        for (int i = 1; i < items.Count; i++)
        {
            if (items[i].Id == id) { box.SelectedIndex = i; return; }
        }
    }

    private void SaveSettings()
    {
        try
        {
            var s = new Settings
            {
                Mode = CurrentMode().ToString(),
                MicId = (MicDevice.SelectedIndex > 0 ? (MicDevice.SelectedItem as DeviceItem)?.Id : "") ?? "",
                SysId = (SysDevice.SelectedIndex > 0 ? (SysDevice.SelectedItem as DeviceItem)?.Id : "") ?? "",
                Rate = SelectedRate(),
                Stereo = Stereo.IsChecked == true,
                Trim = TrimSilence.IsChecked == true,
                Norm = Normalise.IsChecked == true,
                Folder = OutFolder.Text,
                Prefix = Prefix.Text,
                Dataset = DatasetMode.IsChecked == true,
                AskAfterTake = AskAfterTake.IsChecked == true,
                MicGain = MicGain.Value,
                SysGain = SysGain.Value,
            };
            var p = SettingsPath();
            Directory.CreateDirectory(Path.GetDirectoryName(p)!);
            File.WriteAllText(p, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }

    // ── UI plumbing ──────────────────────────────────────────────────────

    private SourceMode CurrentMode() =>
        ModeSys.IsChecked == true ? SourceMode.System :
        ModeBoth.IsChecked == true ? SourceMode.Both : SourceMode.Mic;

    private int SelectedRate() =>
        RatePreset.SelectedItem is ComboBoxItem ci && ci.Tag is int r ? r : 24000;

    private int SelectedChannels() => Stereo.IsChecked == true ? 2 : 1;

    private void Rate_Changed(object sender, RoutedEventArgs e) => UpdateRateHint();

    /// <summary>
    /// The single most useful number on the screen: half the sample rate is a hard ceiling on what
    /// the file can carry, and it is the reason a 24 kHz capture of a sound effect sounds dull.
    /// </summary>
    private void UpdateRateHint()
    {
        if (RateHint == null || RatePreset == null || Stereo == null) return;
        double ceilingKhz = SelectedRate() / 2000.0;
        string ch = SelectedChannels() == 2 ? "Stereo" : "Mono";
        RateHint.Text = $"{ch} 16-bit WAV, carrying everything up to {ceilingKhz:0.##} kHz. "
                      + "Anything above that is not recorded at all.";
    }

    private void Mode_Changed(object sender, RoutedEventArgs e)
    {
        if (!ready) return;
        UpdateRows();
        RestartMonitor();
    }

    private void Device_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (!ready) return;
        RestartMonitor();
    }

    private void Gain_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        // A slider fires ValueChanged while the XAML is still being parsed, so the fields for
        // controls further down the tree are not assigned yet. Every one of them must be checked.
        if (MicGain == null || SysGain == null || MicGainLabel == null || SysGainLabel == null) return;
        MicGainLabel.Text = $"GAIN  {MicGain.Value:0.0}×";
        SysGainLabel.Text = $"GAIN  {SysGain.Value:0.0}×";
    }

    private void Dataset_Changed(object sender, RoutedEventArgs e)
    {
        if (DatasetHint == null) return;
        DatasetHint.Text = DatasetMode.IsChecked == true
            ? "On: numbered takes into wavs/, each appended to metadata.csv as  id|text|text."
            : "Off: one timestamped file per take, ready as an IndexTTS voice prompt.";
    }

    private void UpdateRows()
    {
        var mode = CurrentMode();
        bool mic = mode != SourceMode.System;
        bool sys = mode != SourceMode.Mic;
        MicRow.IsEnabled = mic;
        MicRow.Opacity = mic ? 1.0 : 0.35;
        SysRow.IsEnabled = sys;
        SysRow.Opacity = sys ? 1.0 : 0.35;
    }

    private void RestartMonitor()
    {
        monitor?.Dispose();
        monitor = null;
        micDisp = sysDisp = 0f;
        if (recorder != null) return;

        var mode = CurrentMode();
        monitor = new LevelMonitor(
            mode != SourceMode.System ? DeviceOf(MicDevice) : null,
            mode != SourceMode.Mic ? DeviceOf(SysDevice) : null);
    }

    private MMDevice? DeviceOf(ComboBox box) => (box.SelectedItem as DeviceItem)?.Resolve(enumerator);

    private void Tick(object? sender, EventArgs e)
    {
        float m = recorder?.MicPeak ?? monitor?.MicPeak ?? 0f;
        float s = recorder?.SysPeak ?? monitor?.SysPeak ?? 0f;

        micDisp = Math.Max(m, micDisp * 0.80f);
        sysDisp = Math.Max(s, sysDisp * 0.80f);

        SetMeter(MicFill, MicTrack, micDisp);
        SetMeter(SysFill, SysTrack, sysDisp);

        if (recorder != null) Elapsed.Text = Fmt(recorder.Duration);
    }

    private static void SetMeter(Rectangle fill, System.Windows.Controls.Border track, float peak)
    {
        double w = track.ActualWidth;
        if (w <= 0) return;
        fill.Width = w * Db(peak);
    }

    /// <summary>Amplitude → 0..1 across a −60 dB window; a linear bar makes speech look like nothing.</summary>
    private static double Db(float v)
    {
        if (v <= 1e-5f) return 0;
        double db = 20.0 * Math.Log10(v);
        return Math.Clamp((db + 60.0) / 60.0, 0, 1);
    }

    private static string Fmt(TimeSpan t) => $"{(int)t.TotalMinutes:00}:{t.Seconds:00}.{t.Milliseconds / 100}";

    // ── transport ────────────────────────────────────────────────────────

    private void Record_Click(object sender, RoutedEventArgs e) => Toggle();

    private void Toggle()
    {
        if (recorder == null) StartRecording();
        else StopRecording();
    }

    private void StartRecording()
    {
        var mode = CurrentMode();
        MMDevice? micDev = mode != SourceMode.System ? DeviceOf(MicDevice) : null;
        MMDevice? sysDev = mode != SourceMode.Mic ? DeviceOf(SysDevice) : null;

        if (mode != SourceMode.System && micDev == null)
        {
            Status.Text = "No microphone found. Plug one in and restart the app, or switch to Screen / system audio.";
            return;
        }
        if (mode != SourceMode.Mic && sysDev == null)
        {
            Status.Text = "No playback device found to capture.";
            return;
        }

        monitor?.Dispose();
        monitor = null;

        try
        {
            var rec = new Recorder(SelectedRate(), SelectedChannels(), micDev, sysDev,
                                   (float)MicGain.Value, (float)SysGain.Value);
            rec.Start();
            recorder = rec;
        }
        catch (Exception ex)
        {
            Status.Text = "Could not start: " + ex.Message;
            RestartMonitor();
            return;
        }

        RecordBtn.Content = "■  Stop  &  save";
        RecordBtn.Background = (System.Windows.Media.Brush)FindResource("Good");
        Elapsed.Text = "00:00.0";
        Status.Text = mode switch
        {
            SourceMode.Mic => "Recording microphone…",
            SourceMode.System => "Recording everything playing on this PC…",
            _ => "Recording microphone + system audio, mixed…",
        };
        SetOptionsEnabled(false);
    }

    private void StopRecording()
    {
        var rec = recorder;
        recorder = null;
        if (rec == null) return;

        RecordBtn.Content = "●  Record";
        RecordBtn.Background = (System.Windows.Media.Brush)FindResource("Rec");
        SetOptionsEnabled(true);

        // Clear the transport before the take dialog goes modal, or the window behind it still
        // reads as recording.
        Elapsed.Text = "00:00.0";
        Status.Text = "Saving…";

        try
        {
            rec.Stop();

            var info = new FileInfo(rec.ScratchPath);
            if (!info.Exists || info.Length <= 128)
            {
                Status.Text = "Nothing was captured — the selected device produced no audio.";
                return;
            }

            var (outPath, id) = NextOutputPath();
            var result = AudioPost.Render(outPath: outPath,
                                          scratchPath: rec.ScratchPath,
                                          trim: TrimSilence.IsChecked == true,
                                          normalize: Normalise.IsChecked == true);

            string note = WriteTranscript(outPath, id);
            bool dataset = DatasetMode.IsChecked == true;
            string finalPath = outPath;

            if (AskAfterTake.IsChecked == true)
            {
                var dlg = new TakeDialog(result, outPath, dataset, true) { Owner = this };
                dlg.ShowDialog();
                AskAfterTake.IsChecked = dlg.KeepAsking;   // the dialog can switch itself off
                finalPath = dlg.FinalPath ?? "";

                if (finalPath.Length == 0)
                {
                    Status.Text = "Take discarded.";
                    return;
                }
            }

            if (result.WasSilent)
            {
                Status.Text = $"Saved {Path.GetFileName(finalPath)} — but it is silent. Check the source and the gain.";
            }
            else
            {
                var bits = new List<string>
                {
                    $"{result.Duration.TotalSeconds:0.0} s",
                    $"{result.SampleRate / 1000.0:0.##} kHz {(result.Channels == 2 ? "stereo" : "mono")}",
                    $"peak {result.PeakDb:0.0} dB"
                };
                if (Normalise.IsChecked == true && Math.Abs(result.GainDb) > 0.05) bits.Add($"{result.GainDb:+0.0;-0.0} dB applied");
                if (result.Trimmed) bits.Add("silence trimmed");
                if (note.Length > 0) bits.Add(note);
                Status.Text = $"Saved  {Path.GetFileName(finalPath)}   ·   " + string.Join("  ·  ", bits);
            }

            if (dataset) Transcript.Clear();
        }
        catch (Exception ex)
        {
            Status.Text = "Save failed: " + ex.Message;
        }
        finally
        {
            rec.DeleteScratch();
            rec.Dispose();
            Elapsed.Text = "00:00.0";
            RestartMonitor();
        }
    }

    private void SetOptionsEnabled(bool on)
    {
        MicDevice.IsEnabled = on;
        SysDevice.IsEnabled = on;
        RatePreset.IsEnabled = on;
        Stereo.IsEnabled = on;   // fixed when the capture chain is built, unlike trim/normalise
        ModeMic.IsEnabled = ModeSys.IsEnabled = ModeBoth.IsEnabled = on;
        DatasetMode.IsEnabled = on;
        OutFolder.IsEnabled = on;
        Prefix.IsEnabled = on;
    }

    // ── output paths & transcript ────────────────────────────────────────

    private string OutputFolder()
    {
        var f = OutFolder.Text.Trim();
        return f.Length == 0 ? DefaultFolder() : f;
    }

    private (string path, string id) NextOutputPath() =>
        OutputWriter.NextOutputPath(OutputFolder(), Prefix.Text, DatasetMode.IsChecked == true, DateTime.Now);

    private string WriteTranscript(string wavPath, string id) =>
        OutputWriter.WriteTranscript(OutputFolder(), wavPath, id, Transcript.Text, DatasetMode.IsChecked == true);

    // ── buttons ──────────────────────────────────────────────────────────

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        using var dlg = new System.Windows.Forms.FolderBrowserDialog
        {
            Description = "Where should recordings go?",
            UseDescriptionForTitle = true,
            SelectedPath = Directory.Exists(OutputFolder()) ? OutputFolder() : DefaultFolder()
        };
        if (dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK)
            OutFolder.Text = dlg.SelectedPath;
    }

    private void Open_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            string f = OutputFolder();
            Directory.CreateDirectory(f);
            Process.Start(new ProcessStartInfo("explorer.exe", $"\"{f}\"") { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            Status.Text = "Could not open the folder: " + ex.Message;
        }
    }

    // ── shutdown ─────────────────────────────────────────────────────────

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        timer.Stop();
        if (recorder != null) StopRecording();
        monitor?.Dispose();
        SaveSettings();
        try
        {
            if (hotkeyOk) UnregisterHotKey(new WindowInteropHelper(this).Handle, HotkeyId);
        }
        catch { }
    }
}
