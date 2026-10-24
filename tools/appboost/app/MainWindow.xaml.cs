using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Threading;

namespace AppBoost;

public partial class MainWindow : Window
{
    private readonly AudioService audio = new();
    private readonly ObservableCollection<AppRow> rows = new();
    private readonly DispatcherTimer meters = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private int ticks;

    public MainWindow()
    {
        InitializeComponent();
        List.ItemsSource = rows;
        Load();

        meters.Tick += Tick;
        meters.Start();
    }

    private void Tick(object? sender, EventArgs e)
    {
        foreach (var (pid, level) in audio.ReadLevels())
        {
            foreach (var row in rows)
            {
                if (row.Pid == pid) row.Level = level;
            }
        }

        // Apps come and go while the window is open; two seconds is often enough to notice
        // without rebuilding the list under someone's cursor.
        if (++ticks % 20 == 0) Load();
    }

    private void Load()
    {
        var sessions = audio.ListSessions();

        // Existing rows are updated rather than replaced: refreshing must not yank the engine
        // out from under an app someone is listening to, or reset a dial mid-drag.
        var byPid = rows.ToDictionary(r => r.Pid);
        foreach (var s in sessions)
        {
            if (byPid.TryGetValue(s.Pid, out var existing)) existing.Update(s);
            else rows.Add(new AppRow(audio, s));
        }

        var live = sessions.Select(s => s.Pid).ToHashSet();
        foreach (var gone in rows.Where(r => !live.Contains(r.Pid) && !r.Enabled).ToList())
        {
            rows.Remove(gone);
        }

        Hint.Text = rows.Count == 0
            ? "Nothing is playing. Start some audio, then hit refresh."
            : $"{rows.Count} apps with an audio session.";
    }

    private void Refresh_Click(object sender, RoutedEventArgs e) => Load();

    protected override void OnClosed(EventArgs e)
    {
        meters.Stop();
        audio.Dispose();
        base.OnClosed(e);
    }
}
