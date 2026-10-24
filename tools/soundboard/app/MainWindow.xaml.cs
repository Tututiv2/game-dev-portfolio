using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace SoundBoard
{
    public partial class MainWindow : Window
    {
        // ------------------------------------------------------------ view models

        public sealed class BindingVm : INotifyPropertyChanged
        {
            public BindingCfg Cfg;
            public BindingVm(BindingCfg c) { Cfg = c; }

            public string Id { get { return Cfg.Id; } }
            public string KeyDisplay { get { return Trig.Describe(Cfg.Trigger); } }

            public string SoundName
            {
                get
                {
                    if (string.IsNullOrEmpty(Cfg.Path)) return "— no sound —";
                    var n = Path.GetFileNameWithoutExtension(Cfg.Path);
                    return File.Exists(Cfg.Path) ? n : n + "  (file missing)";
                }
            }

            public string SoundFolder
            {
                get
                {
                    if (string.IsNullOrEmpty(Cfg.Path)) return "click … to pick a file, or select one on the right";
                    try { return Path.GetDirectoryName(Cfg.Path); } catch { return Cfg.Path; }
                }
            }

            public Brush NameBrush
            {
                get
                {
                    if (string.IsNullOrEmpty(Cfg.Path) || !File.Exists(Cfg.Path))
                        return new SolidColorBrush(Color.FromRgb(0xFF, 0x6B, 0x6B));
                    return new SolidColorBrush(Color.FromRgb(0xE7, 0xEC, 0xF5));
                }
            }

            public double Volume
            {
                get { return Cfg.Volume; }
                set { Cfg.Volume = (float)value; Changed("Volume"); Changed("VolumeLabel"); }
            }
            public string VolumeLabel { get { return "vol " + Math.Round(Cfg.Volume * 100) + "%"; } }

            public bool PassThrough
            {
                get { return Cfg.PassThrough; }
                set { Cfg.PassThrough = value; Changed("PassThrough"); }
            }

            bool _sel;
            public bool IsSelected { get { return _sel; } set { _sel = value; Changed("RowBrush"); } }
            public Brush RowBrush
            {
                get
                {
                    return _sel ? new SolidColorBrush(Color.FromRgb(0x4E, 0xE1, 0xA0))
                                : new SolidColorBrush(Color.FromRgb(0x2A, 0x31, 0x40));
                }
            }

            public event PropertyChangedEventHandler PropertyChanged;
            public void Changed(string p)
            {
                var h = PropertyChanged;
                if (h != null) h(this, new PropertyChangedEventArgs(p));
            }
            public void RefreshAll()
            {
                Changed("KeyDisplay"); Changed("SoundName"); Changed("SoundFolder");
                Changed("NameBrush"); Changed("Volume"); Changed("VolumeLabel"); Changed("RowBrush");
            }
        }

        public sealed class SoundItem
        {
            public string Path { get; set; }
            public string Name { get; set; }
            public string FolderName { get; set; }
        }

        enum Capture { None, NewBinding, Rebind, ToggleHotkey, StopHotkey }

        // ------------------------------------------------------------ state

        AppConfig _cfg;
        readonly AudioEngine _audio = new AudioEngine();
        readonly InputHook _hook = new InputHook();
        readonly ObservableCollection<BindingVm> _bindings = new ObservableCollection<BindingVm>();
        readonly List<SoundItem> _library = new List<SoundItem>();

        Capture _capture = Capture.None;
        BindingVm _captureTarget;
        BindingVm _selectedRow;
        RetriggerMode _mode = RetriggerMode.Restart;
        System.Windows.Forms.NotifyIcon _tray;
        bool _loading = true;
        bool _saidWhereItWent;

        static readonly string[] Exts = { ".mp3", ".wav", ".wma", ".m4a", ".aac", ".flac", ".aif", ".aiff" };

        public MainWindow()
        {
            InitializeComponent();
            Loaded += OnLoaded;
            Closing += OnClosing;
            StateChanged += OnStateChanged;
        }

        void OnLoaded(object sender, RoutedEventArgs e)
        {
            _cfg = AppConfig.Load();
            if (_cfg == null)
            {
                _cfg = AppConfig.CreateDefault();
                _cfg.Save();
            }
            if (_cfg.LibraryFolders.Count == 0)
                _cfg.LibraryFolders.Add(Path.Combine(AppConfig.Dir, "Sounds"));

            BindingList.ItemsSource = _bindings;
            foreach (var b in _cfg.Bindings) _bindings.Add(new BindingVm(b));

            MasterSlider.Value = _cfg.MasterVolume;
            _audio.MasterVolume = _cfg.MasterVolume;
            RetriggerBox.SelectedIndex = _cfg.Retrigger == "Overlap" ? 1 : _cfg.Retrigger == "Toggle" ? 2 : 0;
            _mode = (RetriggerMode)RetriggerBox.SelectedIndex;
            ChkTop.IsChecked = _cfg.AlwaysOnTop;
            Topmost = _cfg.AlwaysOnTop;
            ChkTray.IsChecked = _cfg.MinimizeToTray;
            DevBOn.IsChecked = _cfg.SecondaryEnabled;
            DevB.IsEnabled = _cfg.SecondaryEnabled;

            LoadDevices();
            ApplyOutputs();
            RescanLibrary();
            UpdateEnabledUi();
            UpdateHotkeyUi();
            SetupTray();

            _hook.OnTrigger = OnTrigger;
            _hook.Install();
            if (!_hook.Installed)
                Say("WARNING: could not install the global hook — sounds will only trigger when this window is focused.");
            else
                Say("Listening globally. Bindings fire even while Rocket League has focus.");

            _audio.Preload(_bindings.Where(b => !string.IsNullOrEmpty(b.Cfg.Path)).Select(b => b.Cfg.Path));
            _loading = false;
            RefreshEmptyHint();
        }

        void OnClosing(object sender, CancelEventArgs e)
        {
            SaveConfig();
            _hook.Dispose();
            _audio.Dispose();
            if (_tray != null) { _tray.Visible = false; _tray.Dispose(); _tray = null; }
        }

        void OnStateChanged(object sender, EventArgs e)
        {
            if (WindowState != WindowState.Minimized || _cfg == null || !_cfg.MinimizeToTray || _tray == null)
                return;

            Hide();
            // Windows 11 buries new tray icons behind the ^ arrow, so a window that
            // hides itself there just looks lost. Say where it went, once.
            if (!_saidWhereItWent)
            {
                _saidWhereItWent = true;
                try
                {
                    _tray.ShowBalloonTip(5000, "SoundBoard is still running",
                        "It's in the tray — click the ^ arrow by the clock, then double-click the green icon. " +
                        "Your hotkeys still work. Turn this off in ⚙ if you'd rather it stayed on the taskbar.",
                        System.Windows.Forms.ToolTipIcon.Info);
                }
                catch { }
            }
        }

        /// <summary>Called when a second copy is launched — show the one already running.</summary>
        public void RestoreFromAnywhere()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
            // a one-frame topmost flip drags it in front of whatever had focus
            bool keep = _cfg != null && _cfg.AlwaysOnTop;
            Topmost = true;
            Topmost = keep;
            Say("You already had SoundBoard open — this is it.");
        }

        // ------------------------------------------------------------ the hook

        /// <summary>
        /// Runs on the UI thread inside the hook. Decide fast, hand playback to a
        /// worker — a slow hook gets us silently unhooked by Windows.
        /// </summary>
        bool OnTrigger(int code, bool isMouse, bool ctrl, bool alt, bool shift, bool repeat)
        {
            if (_capture != Capture.None)
            {
                if (repeat) return true;
                if (!isMouse && Trig.IsModifierKey(code)) return false;   // modifiers only qualify a key
                if (!isMouse && code == 0x1B) { EndCapture(null); return true; }
                EndCapture(new TriggerCfg { Code = code, IsMouse = isMouse, Ctrl = ctrl, Alt = alt, Shift = shift });
                return true;
            }

            if (_cfg.ToggleHotkey != null && _cfg.ToggleHotkey.Matches(code, isMouse, ctrl, alt, shift))
            {
                if (!repeat) SetEnabled(!_cfg.Enabled);
                return true;
            }
            if (_cfg.StopHotkey != null && _cfg.StopHotkey.Matches(code, isMouse, ctrl, alt, shift))
            {
                if (!repeat) { _audio.StopAll(); Say("Stopped everything."); }
                return true;
            }

            if (!_cfg.Enabled) return false;

            foreach (var vm in _bindings)
            {
                if (!vm.Cfg.Trigger.Matches(code, isMouse, ctrl, alt, shift)) continue;
                if (!repeat) Fire(vm, false);
                return !vm.Cfg.PassThrough;
            }
            return false;
        }

        void Fire(BindingVm vm, bool previewOnly)
        {
            string id = previewOnly ? "preview" : vm.Id;
            string path = vm.Cfg.Path;
            float vol = vm.Cfg.Volume;
            var mode = previewOnly ? RetriggerMode.Restart : _mode;
            Task.Run(() =>
            {
                var err = _audio.Play(id, path, vol, mode, previewOnly);
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    if (err != null) Say("Could not play " + Path.GetFileName(path) + " — " + err);
                    else Say((previewOnly ? "Preview: " : "▶ ") + Path.GetFileNameWithoutExtension(path));
                }));
            });
        }

        // ------------------------------------------------------------ capture

        void BeginCapture(Capture kind, BindingVm target, string sub)
        {
            _capture = kind;
            _captureTarget = target;
            CaptureSub.Text = sub;
            CaptureOverlay.Visibility = Visibility.Visible;
        }

        void EndCapture(TriggerCfg t)
        {
            var kind = _capture;
            var target = _captureTarget;
            _capture = Capture.None;
            _captureTarget = null;
            CaptureOverlay.Visibility = Visibility.Collapsed;

            if (t == null) { Say("Cancelled."); return; }

            switch (kind)
            {
                case Capture.NewBinding:
                    {
                        var cfg = new BindingCfg { Trigger = t };
                        var sel = LibList.SelectedItem as SoundItem;
                        if (sel != null) cfg.Path = sel.Path;
                        _cfg.Bindings.Add(cfg);
                        var vm = new BindingVm(cfg);
                        _bindings.Add(vm);
                        SelectRow(vm);
                        if (sel != null) _audio.Preload(new[] { sel.Path });
                        Say("Bound " + Trig.Describe(t) + (sel != null ? " → " + sel.Name : " — now pick a sound for it."));
                        break;
                    }
                case Capture.Rebind:
                    if (target != null)
                    {
                        target.Cfg.Trigger = t;
                        target.RefreshAll();
                        Say("Rebound to " + Trig.Describe(t));
                    }
                    break;
                case Capture.ToggleHotkey:
                    _cfg.ToggleHotkey = t; UpdateHotkeyUi(); Say("Toggle hotkey: " + Trig.Describe(t));
                    break;
                case Capture.StopHotkey:
                    _cfg.StopHotkey = t; UpdateHotkeyUi(); Say("Stop-all hotkey: " + Trig.Describe(t));
                    break;
            }
            WarnOnDuplicates();
            SaveConfig();
            RefreshEmptyHint();
        }

        void WarnOnDuplicates()
        {
            var seen = new HashSet<string>();
            foreach (var vm in _bindings)
            {
                var k = Trig.Describe(vm.Cfg.Trigger);
                if (!seen.Add(k))
                {
                    Say("Heads up: " + k + " is bound more than once — the first one wins.");
                    return;
                }
            }
        }

        // ------------------------------------------------------------ header

        void BtnEnable_Click(object sender, RoutedEventArgs e) { SetEnabled(!_cfg.Enabled); }

        void SetEnabled(bool on)
        {
            _cfg.Enabled = on;
            if (!on) _audio.StopAll();
            UpdateEnabledUi();
            SaveConfig();
        }

        void UpdateEnabledUi()
        {
            bool on = _cfg.Enabled;
            EnableText.Text = on ? "ENABLED" : "DISABLED";
            var green = (Brush)FindResource("Accent");
            var red = (Brush)FindResource("Danger");
            EnableText.Foreground = on ? green : red;
            Dot.Fill = on ? green : red;
            BtnEnable.Background = new SolidColorBrush(on ? Color.FromRgb(0x17, 0x3A, 0x2C) : Color.FromRgb(0x3A, 0x1A, 0x1A));
            BtnEnable.BorderBrush = on ? (Brush)FindResource("AccentD") : red;
            Title = on ? "SoundBoard — on" : "SoundBoard — OFF";
            if (_tray != null) _tray.Text = on ? "SoundBoard — on" : "SoundBoard — OFF (typing safe)";
            if (!_loading) Say(on ? "Soundboard ON." : "Soundboard OFF — safe to type.");
        }

        void UpdateHotkeyUi()
        {
            BtnToggleHotkey.Content = Trig.Describe(_cfg.ToggleHotkey);
            BtnStopHotkey.Content = Trig.Describe(_cfg.StopHotkey);
            ToggleHint.Text = "toggle: " + Trig.Describe(_cfg.ToggleHotkey);
            StopHint.Text = "stop all: " + Trig.Describe(_cfg.StopHotkey);
        }

        void MasterSlider_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            if (VolText != null) VolText.Text = Math.Round(e.NewValue * 100) + "%";
            _audio.MasterVolume = (float)e.NewValue;
            if (_cfg != null && !_loading) { _cfg.MasterVolume = (float)e.NewValue; SaveConfig(); }
        }

        void BtnStopAll_Click(object sender, RoutedEventArgs e) { _audio.StopAll(); Say("Stopped everything."); }

        void BtnSettings_Click(object sender, RoutedEventArgs e)
        {
            SettingsPanel.Visibility = SettingsPanel.Visibility == Visibility.Visible
                ? Visibility.Collapsed : Visibility.Visible;
        }

        // ------------------------------------------------------------ binding rows

        void BtnAddBinding_Click(object sender, RoutedEventArgs e)
        {
            var sel = LibList.SelectedItem as SoundItem;
            BeginCapture(Capture.NewBinding, null,
                sel != null ? "it will play: " + sel.Name : "no sound selected — you can pick one after");
        }

        static BindingVm RowOf(object sender)
        {
            var fe = sender as FrameworkElement;
            return fe == null ? null : fe.DataContext as BindingVm;
        }

        void Row_Click(object sender, MouseButtonEventArgs e)
        {
            var vm = RowOf(sender);
            if (vm != null) SelectRow(vm);
        }

        void SelectRow(BindingVm vm)
        {
            foreach (var b in _bindings) if (b.IsSelected && b != vm) b.IsSelected = false;
            _selectedRow = vm;
            if (vm != null) vm.IsSelected = true;
        }

        void BtnRebind_Click(object sender, RoutedEventArgs e)
        {
            var vm = RowOf(sender);
            if (vm == null) return;
            SelectRow(vm);
            BeginCapture(Capture.Rebind, vm, "new key for: " + vm.SoundName);
        }

        void RowVolume_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
        {
            var vm = RowOf(sender);
            if (vm == null || _loading) return;
            vm.Changed("VolumeLabel");
            SaveConfig();
        }

        void RowPass_Click(object sender, RoutedEventArgs e) { SaveConfig(); }

        void BtnPreviewRow_Click(object sender, RoutedEventArgs e)
        {
            var vm = RowOf(sender);
            if (vm == null) return;
            SelectRow(vm);
            if (string.IsNullOrEmpty(vm.Cfg.Path)) { Say("That binding has no sound yet."); return; }
            Fire(vm, true);
        }

        void BtnPickFileRow_Click(object sender, RoutedEventArgs e)
        {
            var vm = RowOf(sender);
            if (vm == null) return;
            SelectRow(vm);
            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Title = "Pick a sound",
                Filter = "Audio|*.mp3;*.wav;*.wma;*.m4a;*.aac;*.flac;*.aif;*.aiff|All files|*.*"
            };
            if (_cfg.LibraryFolders.Count > 0 && Directory.Exists(_cfg.LibraryFolders[0]))
                dlg.InitialDirectory = _cfg.LibraryFolders[0];
            if (dlg.ShowDialog() == true) AssignTo(vm, dlg.FileName);
        }

        void BtnDeleteRow_Click(object sender, RoutedEventArgs e)
        {
            var vm = RowOf(sender);
            if (vm == null) return;
            _audio.Stop(vm.Id);
            _bindings.Remove(vm);
            _cfg.Bindings.RemoveAll(b => b.Id == vm.Cfg.Id);
            if (_selectedRow == vm) _selectedRow = null;
            SaveConfig();
            RefreshEmptyHint();
            Say("Removed " + Trig.Describe(vm.Cfg.Trigger));
        }

        void AssignTo(BindingVm vm, string path)
        {
            vm.Cfg.Path = path;
            vm.RefreshAll();
            _audio.Preload(new[] { path });
            SaveConfig();
            Say(Trig.Describe(vm.Cfg.Trigger) + " → " + Path.GetFileNameWithoutExtension(path));
        }

        void RefreshEmptyHint()
        {
            EmptyHint.Visibility = _bindings.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        // ------------------------------------------------------------ library

        void RescanLibrary()
        {
            _library.Clear();
            foreach (var folder in _cfg.LibraryFolders)
            {
                if (string.IsNullOrWhiteSpace(folder) || !Directory.Exists(folder)) continue;
                ScanFolder(folder, 0);
            }
            _library.Sort((a, b) => string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase));
            ApplyFilter();
            LibHeader.Text = "SOUND LIBRARY  ·  " + _library.Count + " files";
        }

        void ScanFolder(string folder, int depth)
        {
            try
            {
                foreach (var f in Directory.EnumerateFiles(folder))
                {
                    var ext = Path.GetExtension(f);
                    if (Array.IndexOf(Exts, ext.ToLowerInvariant()) < 0) continue;
                    _library.Add(new SoundItem
                    {
                        Path = f,
                        Name = Path.GetFileNameWithoutExtension(f),
                        FolderName = Path.GetFileName(Path.GetDirectoryName(f))
                    });
                    if (_library.Count > 6000) return;
                }
                if (depth < 1)
                    foreach (var d in Directory.EnumerateDirectories(folder)) ScanFolder(d, depth + 1);
            }
            catch { /* unreadable folder — skip it */ }
        }

        void ApplyFilter()
        {
            var q = (SearchBox.Text ?? "").Trim();
            IEnumerable<SoundItem> items = _library;
            if (q.Length > 0)
            {
                var parts = q.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                items = _library.Where(s => parts.All(p => s.Name.IndexOf(p, StringComparison.OrdinalIgnoreCase) >= 0));
            }
            LibList.ItemsSource = items.ToList();
        }

        void SearchBox_Changed(object sender, TextChangedEventArgs e)
        {
            if (SearchHint != null)
                SearchHint.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Visible : Visibility.Collapsed;
            if (_cfg != null) ApplyFilter();
        }

        void LibList_SelectionChanged(object sender, SelectionChangedEventArgs e) { }

        void LibList_DoubleClick(object sender, MouseButtonEventArgs e) { PreviewLibrary(); }

        void BtnPreviewLib_Click(object sender, RoutedEventArgs e) { PreviewLibrary(); }

        void PreviewLibrary()
        {
            var s = LibList.SelectedItem as SoundItem;
            if (s == null) { Say("Pick a sound in the list first."); return; }
            var path = s.Path;
            Task.Run(() =>
            {
                var err = _audio.Play("preview", path, 1f, RetriggerMode.Restart, true);
                Dispatcher.BeginInvoke(new Action(() =>
                    Say(err == null ? "Preview: " + Path.GetFileNameWithoutExtension(path)
                                    : "Could not play that file — " + err)));
            });
        }

        void BtnAssign_Click(object sender, RoutedEventArgs e)
        {
            var s = LibList.SelectedItem as SoundItem;
            if (s == null) { Say("Pick a sound in the list first."); return; }
            if (_selectedRow == null) { Say("Click a binding on the left first (or use “Bind this sound to a key”)."); return; }
            AssignTo(_selectedRow, s.Path);
        }

        void BtnBindThis_Click(object sender, RoutedEventArgs e)
        {
            var s = LibList.SelectedItem as SoundItem;
            if (s == null) { Say("Pick a sound in the list first."); return; }
            BeginCapture(Capture.NewBinding, null, "it will play: " + s.Name);
        }

        void BtnAddFolder_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Title = "Pick ANY file inside the folder you want to add",
                CheckFileExists = false,
                FileName = "select this folder",
                Filter = "All files|*.*"
            };
            if (dlg.ShowDialog() == true)
            {
                var dir = Path.GetDirectoryName(dlg.FileName);
                if (!string.IsNullOrEmpty(dir) && !_cfg.LibraryFolders.Contains(dir))
                {
                    _cfg.LibraryFolders.Add(dir);
                    SaveConfig();
                    RescanLibrary();
                    Say("Added library folder: " + dir);
                }
            }
        }

        void BtnRescan_Click(object sender, RoutedEventArgs e) { RescanLibrary(); Say("Rescanned."); }

        // ------------------------------------------------------------ settings

        void LoadDevices()
        {
            var devs = AudioEngine.Devices();
            DevA.ItemsSource = devs;
            DevB.ItemsSource = AudioEngine.Devices();
            DevA.SelectedItem = devs.FirstOrDefault(d => d.Id == _cfg.PrimaryDeviceId) ?? devs[0];
            var listB = (List<AudioEngine.DeviceInfo>)DevB.ItemsSource;
            DevB.SelectedItem = listB.FirstOrDefault(d => d.Id == _cfg.SecondaryDeviceId) ?? listB[0];
        }

        void ApplyOutputs()
        {
            var a = DevA.SelectedItem as AudioEngine.DeviceInfo;
            var b = DevB.SelectedItem as AudioEngine.DeviceInfo;
            _audio.SetOutputs(a != null ? a.Id : "", _cfg.SecondaryEnabled && b != null ? b.Id : null);
            DeviceStatus.Text = "out: " + _audio.PrimaryStatus +
                                (_cfg.SecondaryEnabled ? "   +   " + _audio.SecondaryStatus : "");
        }

        void Dev_Changed(object sender, SelectionChangedEventArgs e)
        {
            if (_loading || _cfg == null) return;
            var a = DevA.SelectedItem as AudioEngine.DeviceInfo;
            var b = DevB.SelectedItem as AudioEngine.DeviceInfo;
            _cfg.PrimaryDeviceId = a != null ? a.Id : "";
            _cfg.SecondaryDeviceId = b != null ? b.Id : "";
            ApplyOutputs();
            SaveConfig();
        }

        void DevBOn_Click(object sender, RoutedEventArgs e)
        {
            _cfg.SecondaryEnabled = DevBOn.IsChecked == true;
            DevB.IsEnabled = _cfg.SecondaryEnabled;
            ApplyOutputs();
            SaveConfig();
        }

        void Retrigger_Changed(object sender, SelectionChangedEventArgs e)
        {
            _mode = (RetriggerMode)Math.Max(0, RetriggerBox.SelectedIndex);
            if (_loading || _cfg == null) return;
            _cfg.Retrigger = _mode.ToString();
            SaveConfig();
        }

        void BtnToggleHotkey_Click(object sender, RoutedEventArgs e)
        { BeginCapture(Capture.ToggleHotkey, null, "this key turns the whole board on and off"); }

        void BtnStopHotkey_Click(object sender, RoutedEventArgs e)
        { BeginCapture(Capture.StopHotkey, null, "this key cuts every playing sound"); }

        void ChkTop_Click(object sender, RoutedEventArgs e)
        {
            _cfg.AlwaysOnTop = ChkTop.IsChecked == true;
            Topmost = _cfg.AlwaysOnTop;
            SaveConfig();
        }

        void ChkTray_Click(object sender, RoutedEventArgs e)
        {
            _cfg.MinimizeToTray = ChkTray.IsChecked == true;
            SaveConfig();
        }

        void BtnOpenFolder_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                { FileName = AppConfig.Dir, UseShellExecute = true });
            }
            catch { }
        }

        // ------------------------------------------------------------ tray

        void SetupTray()
        {
            try
            {
                var icon = MakeIcon();
                _tray = new System.Windows.Forms.NotifyIcon
                {
                    Icon = icon,
                    Visible = true,
                    Text = "SoundBoard"
                };
                _tray.DoubleClick += (s, e) => RestoreWindow();

                var menu = new System.Windows.Forms.ContextMenuStrip();
                menu.Items.Add("Show", null, (s, e) => RestoreWindow());
                menu.Items.Add("Toggle on/off", null, (s, e) => Dispatcher.Invoke(() => SetEnabled(!_cfg.Enabled)));
                menu.Items.Add("Stop all sounds", null, (s, e) => _audio.StopAll());
                menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
                menu.Items.Add("Exit", null, (s, e) => Dispatcher.Invoke(() => Close()));
                _tray.ContextMenuStrip = menu;
            }
            catch { _tray = null; }
        }

        void RestoreWindow()
        {
            Dispatcher.Invoke(() =>
            {
                Show();
                WindowState = WindowState.Normal;
                Activate();
            });
        }

        static System.Drawing.Icon MakeIcon()
        {
            using (var bmp = new System.Drawing.Bitmap(32, 32))
            {
                using (var g = System.Drawing.Graphics.FromImage(bmp))
                {
                    g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                    g.Clear(System.Drawing.Color.Transparent);
                    using (var b = new System.Drawing.SolidBrush(System.Drawing.Color.FromArgb(78, 225, 160)))
                        g.FillEllipse(b, 1, 1, 30, 30);
                    using (var f = new System.Drawing.Font("Segoe UI", 16, System.Drawing.FontStyle.Bold))
                    using (var t = new System.Drawing.SolidBrush(System.Drawing.Color.FromArgb(16, 19, 26)))
                        g.DrawString("♪", f, t, new System.Drawing.PointF(5, 1));
                }
                return System.Drawing.Icon.FromHandle(bmp.GetHicon());
            }
        }

        // ------------------------------------------------------------ misc

        void SaveConfig()
        {
            if (_cfg == null || _loading) return;
            _cfg.Bindings = _bindings.Select(b => b.Cfg).ToList();
            _cfg.Save();
        }

        void Say(string msg)
        {
            Status.Text = msg;
        }
    }
}
