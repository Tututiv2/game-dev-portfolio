using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using NAudio.CoreAudioApi;

namespace VolumeIncreaser;

public partial class MainWindow : Window
{
    private readonly VirtualCableGainEngine _gainEngine = new();
    private readonly MMDeviceEnumerator _deviceEnumerator = new();
    private bool _suppressMasterCallback;

    public MainWindow()
    {
        InitializeComponent();
        Loaded += OnLoaded;
        Closed += OnClosed;
    }

    private void OnClosed(object? sender, EventArgs e)
    {
        _gainEngine.Dispose();
        _deviceEnumerator.Dispose();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        try
        {
            PopulateCombos();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Audio devices could not be read: " + ex.Message, "Volume Increaser", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void PopulateCombos()
    {
        var render = ToItems(_deviceEnumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active));
        var capture = ToItems(_deviceEnumerator.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active));

        // Every playback device can also be a source, via loopback. This is what removes
        // the VB-Cable requirement — any output you are not currently listening to works
        // as the "cable", including the virtual ones Sonar and NVIDIA already installed.
        var sources = new List<AudioDeviceItem>();
        foreach (var r in render)
        {
            sources.Add(new AudioDeviceItem(r.Id, r.Name + "   [loopback]", isLoopback: true));
        }

        sources.AddRange(capture);

        RenderDeviceCombo.DisplayMemberPath = nameof(AudioDeviceItem.Name);
        RenderDeviceCombo.SelectedValuePath = nameof(AudioDeviceItem.Id);
        OverdriveOutCombo.DisplayMemberPath = nameof(AudioDeviceItem.Name);
        OverdriveOutCombo.SelectedValuePath = nameof(AudioDeviceItem.Id);
        CaptureDeviceCombo.DisplayMemberPath = nameof(AudioDeviceItem.Name);
        CaptureDeviceCombo.SelectedValuePath = nameof(AudioDeviceItem.Id);

        RenderDeviceCombo.ItemsSource = render;
        OverdriveOutCombo.ItemsSource = new List<AudioDeviceItem>(render);
        CaptureDeviceCombo.ItemsSource = sources;

        if (_deviceEnumerator.HasDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia))
        {
            using var def = _deviceEnumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            SetComboById(RenderDeviceCombo, def.ID);
            SetComboById(OverdriveOutCombo, def.ID);
        }

        if (sources.Count > 0)
        {
            var cable = sources.FirstOrDefault(c =>
                c.Name.Contains("CABLE", StringComparison.OrdinalIgnoreCase) ||
                c.Name.Contains("VB-Audio", StringComparison.OrdinalIgnoreCase));
            CaptureDeviceCombo.SelectedValue = (cable ?? sources[0]).Id;
        }

        UpdateGainLabel();
        TryPullMasterToSlider();
    }

    private static List<AudioDeviceItem> ToItems(MMDeviceCollection col)
    {
        var list = new List<AudioDeviceItem>(col.Count);
        for (var i = 0; i < col.Count; i++)
        {
            using var d = col[i];
            list.Add(new AudioDeviceItem(d.ID, d.FriendlyName));
        }

        return list;
    }

    private static void SetComboById(ComboBox combo, string id)
    {
        combo.SelectedValue = id;
    }

    private void RenderDeviceCombo_OnSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        TryPullMasterToSlider();
    }

    private void TryPullMasterToSlider()
    {
        if (RenderDeviceCombo.SelectedValue is not string id)
        {
            return;
        }

        try
        {
            using var d = _deviceEnumerator.GetDevice(id);
            _suppressMasterCallback = true;
            try
            {
                var v = d.AudioEndpointVolume.MasterVolumeLevelScalar;
                MasterSlider.Value = (int)float.Round(v * 100f, MidpointRounding.AwayFromZero);
                UpdateMasterLabel();
            }
            finally
            {
                _suppressMasterCallback = false;
            }
        }
        catch
        {
            // Device may have been removed or may not expose endpoint volume; leave slider unchanged.
        }
    }

    private void MasterSlider_OnValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (!IsLoaded || _suppressMasterCallback)
        {
            return;
        }

        if (RenderDeviceCombo.SelectedValue is not string id)
        {
            return;
        }

        using var d = _deviceEnumerator.GetDevice(id);
        var level = (float)(MasterSlider.Value / 100.0);
        SystemVolumeHelper.SetMasterToScalar(d, level);
        UpdateMasterLabel();
    }

    private void UpdateMasterLabel()
    {
        MasterPercentLabel.Text = (int)MasterSlider.Value + "%";
    }

    private void MaxMasterButton_OnClick(object sender, RoutedEventArgs e)
    {
        _suppressMasterCallback = true;
        try
        {
            MasterSlider.Value = 100;
        }
        finally
        {
            _suppressMasterCallback = false;
        }

        if (RenderDeviceCombo.SelectedValue is not string id)
        {
            return;
        }

        using var d = _deviceEnumerator.GetDevice(id);
        SystemVolumeHelper.SetMasterToScalar(d, 1f);
        UpdateMasterLabel();
    }

    private void MaxAllSessionsButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (RenderDeviceCombo.SelectedValue is not string id)
        {
            MessageBox.Show(this, "Select a render device first.", "Volume Increaser", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        try
        {
            using var d = _deviceEnumerator.GetDevice(id);
            SystemVolumeHelper.MaximizeAllAppSessionsOnDevice(d);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Could not adjust sessions: " + ex.Message, "Volume Increaser", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void VbcLink_OnClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "https://vb-audio.com/Cable/",
                UseShellExecute = true
            });
        }
        catch
        {
            // ignored
        }
    }

    private void GainSlider_OnValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        UpdateGainLabel();
        if (_gainEngine.IsRunning)
        {
            _gainEngine.SetGain(EffectiveGain);
        }
    }

    private void UpdateGainLabel()
    {
        GainPercentLabel.Text = (int)GainSlider.Value + "%";
    }

    private float EffectiveGain => (float)(GainSlider.Value / 100.0);

    private void StartBoostButton_OnClick(object sender, RoutedEventArgs e)
    {
        if (CaptureDeviceCombo.SelectedValue is not string capId)
        {
            MessageBox.Show(this, "Select a capture device (cable).", "Volume Increaser", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        if (OverdriveOutCombo.SelectedValue is not string outId)
        {
            MessageBox.Show(this, "Select a physical output to play to.", "Volume Increaser", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var sourceIsPlayback = (CaptureDeviceCombo.SelectedItem as AudioDeviceItem)?.IsLoopback ?? false;

        try
        {
            _gainEngine.Start(
                _deviceEnumerator.GetDevice(capId),
                _deviceEnumerator.GetDevice(outId),
                EffectiveGain,
                sourceIsPlayback);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Could not start: " + ex.Message, "Volume Increaser", MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        StartBoostButton.IsEnabled = false;
        StopBoostButton.IsEnabled = true;
        EngineStatus.Text = "Running (gain " + (int)GainSlider.Value + "%)";
    }

    private void StopBoostButton_OnClick(object sender, RoutedEventArgs e)
    {
        _gainEngine.Stop();
        StartBoostButton.IsEnabled = true;
        StopBoostButton.IsEnabled = false;
        EngineStatus.Text = "Idle";
    }

    private sealed class AudioDeviceItem
    {
        public string Id { get; }
        public string Name { get; }
        /// <summary>True when this is a playback device being captured via loopback.</summary>
        public bool IsLoopback { get; }

        public AudioDeviceItem(string id, string name, bool isLoopback = false)
        {
            Id = id;
            Name = name;
            IsLoopback = isLoopback;
        }
    }
}
