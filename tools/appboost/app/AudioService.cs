using System.Collections.Concurrent;
using System.Diagnostics;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace AppBoost;

/// <summary>A snapshot of one audio session. Plain data, safe to hand to the UI thread.</summary>
internal sealed record SessionInfo(int Pid, string Name, string DeviceName, bool IsPlaying);

/// <summary>
/// Owns every COM object in the app, on one dedicated MTA thread.
///
/// This is not tidiness, it is required. WPF's UI thread is STA; ActivateAudioInterfaceAsync
/// completes on an MTA worker thread. Touching the resulting IAudioClient from the STA thread
/// needs a cross-apartment proxy, and none is registered for it — the QueryInterface comes back
/// E_NOINTERFACE on an interface the object plainly implements. The same trap catches NAudio's
/// MMDevice and AudioSessionControl.
///
/// So: the UI thread never holds a COM reference. It posts work here and gets records back.
/// Engine pump threads are MTA too, which puts them in this same apartment, so interface
/// pointers pass between them with no marshalling at all.
/// </summary>
internal sealed class AudioService : IDisposable
{
    private readonly BlockingCollection<Action> queue = new();
    private readonly Thread thread;

    private MMDeviceEnumerator? enumerator;
    private readonly Dictionary<int, (MMDevice device, AudioSessionControl session)> known = new();
    private readonly Dictionary<int, BoostEngine> engines = new();

    public AudioService()
    {
        thread = new Thread(Loop)
        {
            IsBackground = true,
            Name = "AppBoost audio (MTA)",
        };
        thread.SetApartmentState(ApartmentState.MTA);
        thread.Start();
    }

    private void Loop()
    {
        enumerator = new MMDeviceEnumerator();
        foreach (var work in queue.GetConsumingEnumerable())
        {
            work();
        }
    }

    private T Run<T>(Func<T> work)
    {
        var done = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        queue.Add(() =>
        {
            try { done.SetResult(work()); }
            catch (Exception ex) { done.SetException(ex); }
        });

        return done.Task.GetAwaiter().GetResult();
    }

    private void Run(Action work) => Run<object?>(() => { work(); return null; });

    /// <summary>
    /// Every app making sound, across every active render endpoint.
    ///
    /// Matched on the session's own pid, not on the process that owns a window: Chrome and
    /// Spotify both render from a child process, so the window pid has no session at all.
    /// </summary>
    public IReadOnlyList<SessionInfo> ListSessions() => Run(() =>
    {
        var found = new List<SessionInfo>();
        var seen = new HashSet<int>();

        foreach (var device in enumerator!.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
        {
            SessionCollection sessions;
            try { sessions = device.AudioSessionManager.Sessions; }
            catch { continue; }

            for (var i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                int pid;
                try { pid = (int)s.GetProcessID; }
                catch { continue; }
                if (pid == 0 || !seen.Add(pid)) continue;

                string name;
                try { name = Process.GetProcessById(pid).ProcessName; }
                catch { continue; }

                // Our own render stream, and the mixer's, are not things to boost.
                if (pid == Environment.ProcessId || name is "audiodg") continue;

                // A live engine keeps the device and session it started with.
                if (!engines.ContainsKey(pid)) known[pid] = (device, s);

                bool playing;
                try { playing = s.State == AudioSessionState.AudioSessionStateActive; }
                catch { playing = false; }

                found.Add(new SessionInfo(pid, name, device.FriendlyName, playing));
            }
        }

        return (IReadOnlyList<SessionInfo>)found;
    });

    /// <summary>
    /// Current output level per app, for the meters.
    ///
    /// This exists because of a real failure: the dial was dragged for several minutes against
    /// a paused Spotify while the sound in the room was coming from Chrome. Nothing on screen
    /// said which app was actually making noise, so the boost looked broken when it was aimed
    /// at silence. A meter per row makes that impossible to get wrong.
    ///
    /// A boosted app reads from the engine, because its session meter reflects the 3% we left
    /// behind rather than the copy we are playing.
    /// </summary>
    public IReadOnlyList<(int Pid, float Level)> ReadLevels() => Run(() =>
    {
        var levels = new List<(int, float)>(known.Count);
        foreach (var (pid, entry) in known)
        {
            float level;
            if (engines.TryGetValue(pid, out var engine))
            {
                level = engine.OutPeak;
            }
            else
            {
                try { level = entry.session.AudioMeterInformation.MasterPeakValue; }
                catch { level = 0; }
            }

            levels.Add((pid, level));
        }

        return (IReadOnlyList<(int, float)>)levels;
    });

    /// <summary>Engages the boost for one app. Throws if the app's session has gone.</summary>
    public void StartBoost(int pid, float dial) => Run(() =>
    {
        if (engines.ContainsKey(pid)) return;
        if (!known.TryGetValue(pid, out var target))
        {
            throw new InvalidOperationException("That app no longer has an audio session.");
        }

        var engine = new BoostEngine(target.device, target.session, pid) { Dial = dial };
        try
        {
            engine.Start();
        }
        catch
        {
            engine.Dispose();
            throw;
        }

        engines[pid] = engine;
    });

    public void StopBoost(int pid) => Run(() =>
    {
        if (engines.Remove(pid, out var engine)) engine.Dispose();
    });

    public void SetDial(int pid, float dial) => Run(() =>
    {
        if (engines.TryGetValue(pid, out var engine)) engine.Dial = dial;
    });

    public void Dispose()
    {
        // Every engine has left an app parked at 3% volume. Restoring that is the one piece of
        // teardown the user would otherwise have to do by hand.
        Run(() =>
        {
            foreach (var engine in engines.Values) engine.Dispose();
            engines.Clear();
        });

        queue.CompleteAdding();
        thread.Join(2000);
        queue.Dispose();
    }
}
