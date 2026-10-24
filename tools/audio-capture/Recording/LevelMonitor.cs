using System;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace AudioCapture.Recording;

/// <summary>
/// Opens the selected endpoints purely to drive the level meters, so you can see the mic is alive
/// before you commit to a take. Writes nothing.
/// </summary>
public sealed class LevelMonitor : IDisposable
{
    private IWaveIn? micCap;
    private IWaveIn? sysCap;

    public volatile float MicPeak;
    public volatile float SysPeak;

    public LevelMonitor(MMDevice? micDevice, MMDevice? systemDevice)
    {
        if (micDevice != null)
        {
            try
            {
                var c = new WasapiCapture(micDevice, true, 50);
                Hook(c, v => MicPeak = v);
                c.StartRecording();
                micCap = c;
            }
            catch { micCap = null; }
        }

        if (systemDevice != null)
        {
            try
            {
                var c = new WasapiLoopbackCapture(systemDevice);
                Hook(c, v => SysPeak = v);
                c.StartRecording();
                sysCap = c;
            }
            catch { sysCap = null; }
        }
    }

    private static void Hook(IWaveIn cap, Action<float> set)
    {
        WaveFormat fmt = cap.WaveFormat;
        if (fmt is WaveFormatExtensible ext)
        {
            try { fmt = ext.ToStandardWaveFormat(); } catch { }
        }
        cap.DataAvailable += (_, e) =>
        {
            if (e.BytesRecorded > 0) set(Recorder.PeakOf(e.Buffer, e.BytesRecorded, fmt));
        };
    }

    public void Dispose()
    {
        foreach (var c in new[] { micCap, sysCap })
        {
            if (c == null) continue;
            try { c.StopRecording(); } catch { }
            try { c.Dispose(); } catch { }
        }
        micCap = null;
        sysCap = null;
        MicPeak = 0f;
        SysPeak = 0f;
    }
}
