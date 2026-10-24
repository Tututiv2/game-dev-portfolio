using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace AppBoost;

/// <summary>
/// One app in the list. Deliberately holds no COM reference of any kind — everything it needs
/// goes through <see cref="AudioService"/>, which owns them all on its own MTA thread. See the
/// comment there for why the UI thread must not touch them.
/// </summary>
internal sealed class AppRow : INotifyPropertyChanged
{
    private readonly AudioService audio;
    private SessionInfo info;

    public AppRow(AudioService audio, SessionInfo info)
    {
        this.audio = audio;
        this.info = info;
    }

    public int Pid => info.Pid;

    public string Name => info.Name;

    public string DeviceName => info.DeviceName;

    public string Status => enabled ? $"boosting {percent:F0}%" : level > 0.001f ? "playing" : "silent";

    private float level;

    /// <summary>0..1 output level. The meter is the only thing that proves an app makes sound.</summary>
    public float Level
    {
        get => level;
        set
        {
            var wasAudible = level > 0.001f;
            level = value;
            Changed();
            if (wasAudible != value > 0.001f) Changed(nameof(Status));
        }
    }

    public void Update(SessionInfo latest)
    {
        info = latest;
        Changed(nameof(Status));
    }

    private double percent = 100;

    public double Percent
    {
        get => percent;
        set
        {
            if (Math.Abs(percent - value) < 0.5) return;
            percent = value;
            if (enabled) audio.SetDial(Pid, (float)(value / 100.0));
            Changed();
            Changed(nameof(PercentText));
            Changed(nameof(Status));
        }
    }

    public string PercentText => $"{percent:F0}%";

    private bool enabled;

    public bool Enabled
    {
        get => enabled;
        set
        {
            if (enabled == value) return;

            if (value)
            {
                try
                {
                    audio.StartBoost(Pid, (float)(percent / 100.0));
                    enabled = true;
                    Error = null;
                }
                catch (Exception ex)
                {
                    enabled = false;
                    Error = ex.Message;
                }

                Changed(nameof(Error));
                Changed(nameof(HasError));
            }
            else
            {
                audio.StopBoost(Pid);
                enabled = false;
            }

            Changed();
            Changed(nameof(Status));
        }
    }

    public string? Error { get; private set; }

    public bool HasError => Error is not null;

    public event PropertyChangedEventHandler? PropertyChanged;

    private void Changed([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
