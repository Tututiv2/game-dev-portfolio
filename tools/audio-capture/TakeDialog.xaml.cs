using System;
using System.IO;
using System.Windows;
using System.Windows.Input;
using AudioCapture.Recording;
using NAudio.Wave;

namespace AudioCapture;

/// <summary>
/// Shown after a take is written. Exists because a folder of voice_20260807_010516.wav files is
/// unreadable the moment there is more than one of them — so this is the one moment you still know
/// what the take was, and it lets you name it, hear it, or throw it away.
/// </summary>
public partial class TakeDialog : Window
{
    private readonly bool datasetMode;
    private WaveOutEvent? player;
    private AudioFileReader? reader;

    /// <summary>Where the file ended up. Null if the take was discarded.</summary>
    public string? FinalPath { get; private set; }

    public bool KeepAsking => AskAgain.IsChecked == true;

    public TakeDialog(AudioPost.Result result, string wavPath, bool dataset, bool keepAsking)
    {
        InitializeComponent();

        datasetMode = dataset;
        FinalPath = wavPath;
        AskAgain.IsChecked = keepAsking;

        var bits = new[]
        {
            $"{result.Duration.TotalSeconds:0.0} s",
            $"{result.SampleRate / 1000.0:0.##} kHz {(result.Channels == 2 ? "stereo" : "mono")} 16-bit",
            $"peak {result.PeakDb:0.0} dBFS",
        };

        if (result.WasSilent)
        {
            Dot.Fill = (System.Windows.Media.Brush)FindResource("Rec");
            Headline.Text = "Saved, but it is silent";
            Details.Text = string.Join("   ·   ", bits) + "  —  check the source and the gain.";
        }
        else
        {
            Headline.Text = "Saved";
            Details.Text = string.Join("   ·   ", bits)
                         + (Math.Abs(result.GainDb) > 0.05 ? $"   ·   {result.GainDb:+0.0;-0.0} dB applied" : "")
                         + (result.Trimmed ? "   ·   silence trimmed" : "");
        }

        NameBox.Text = Path.GetFileNameWithoutExtension(wavPath);

        if (datasetMode)
        {
            // The number is the key into metadata.csv; renaming here would orphan the row.
            NameBox.IsEnabled = false;
            NameBox.ToolTip = "Dataset takes are numbered to match metadata.csv, so they are not renamed here.";
        }

        Loaded += (_, _) =>
        {
            if (datasetMode) KeepBtn.Focus();
            else { NameBox.Focus(); NameBox.SelectAll(); }
        };
    }

    private void Play_Click(object sender, RoutedEventArgs e)
    {
        if (player != null) { StopPlayback(); return; }
        if (FinalPath == null || !File.Exists(FinalPath)) return;

        try
        {
            reader = new AudioFileReader(FinalPath);
            player = new WaveOutEvent { DesiredLatency = 120 };
            player.Init(reader);
            player.PlaybackStopped += (_, _) => Dispatcher.Invoke(StopPlayback);
            player.Play();
            PlayBtn.Content = "■  Stop";
        }
        catch
        {
            StopPlayback();
        }
    }

    private void StopPlayback()
    {
        try { player?.Stop(); } catch { }
        try { player?.Dispose(); } catch { }
        try { reader?.Dispose(); } catch { }
        player = null;
        reader = null;
        PlayBtn.Content = "▶  Play";
    }

    private void Keep_Click(object sender, RoutedEventArgs e)
    {
        StopPlayback();

        if (!datasetMode && FinalPath != null)
        {
            try { FinalPath = OutputWriter.RenameTake(FinalPath, NameBox.Text); }
            catch { /* keep the original name rather than lose the take */ }
        }

        DialogResult = true;
        Close();
    }

    private void Discard_Click(object sender, RoutedEventArgs e)
    {
        StopPlayback();

        if (FinalPath != null)
        {
            try
            {
                if (File.Exists(FinalPath)) File.Delete(FinalPath);
                string txt = Path.ChangeExtension(FinalPath, ".txt");
                if (File.Exists(txt)) File.Delete(txt);
            }
            catch { }
        }

        FinalPath = null;
        DialogResult = true;
        Close();
    }

    protected override void OnClosed(EventArgs e)
    {
        StopPlayback();
        base.OnClosed(e);
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.Key == Key.Escape) { Keep_Click(this, null!); e.Handled = true; return; }
        base.OnKeyDown(e);
    }
}
