using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace VolumeIncreaser;

/// <summary>
/// Takes the signal from one device, multiplies it, and plays it out of another.
///
/// The source can be either:
///   * a real capture device — e.g. the "CABLE Output" side of VB-Audio Virtual Cable, or
///   * ANY playback device, captured via WASAPI loopback.
///
/// The loopback mode is the one that matters, because it means no virtual cable has to be
/// installed at all: point a single app at a playback device you are not listening to (a
/// spare HDMI output, or a SteelSeries Sonar / NVIDIA virtual endpoint), loopback-capture
/// that device, and play it to the real speakers with gain. Only that app gets boosted and
/// everything else on the PC is untouched.
///
/// The one rule is that the source and the destination must be different devices, or you
/// build a feedback loop that gets louder until something clips.
/// </summary>
internal sealed class VirtualCableGainEngine : IDisposable
{
    private IWaveIn? _capture;
    private WasapiOut? _out;
    private BufferedWaveProvider? _buffer;
    private VolumeSampleProvider? _volume;
    private MMDevice? _ownedCaptureDevice;
    private MMDevice? _ownedOutputDevice;

    public bool IsRunning => _out?.PlaybackState == PlaybackState.Playing;

    public void Start(MMDevice captureFromDevice, MMDevice playToDevice, float gain, bool sourceIsPlaybackDevice)
    {
        Stop();

        if (string.Equals(captureFromDevice.ID, playToDevice.ID, StringComparison.OrdinalIgnoreCase))
        {
            // Guarded here rather than in the UI so no future caller can wire up a howl.
            throw new InvalidOperationException(
                "Source and destination are the same device. That feeds the boosted audio back into " +
                "itself. Pick a different device to capture from.");
        }

        try
        {
            _ownedCaptureDevice = captureFromDevice;
            _ownedOutputDevice = playToDevice;
            _capture = sourceIsPlaybackDevice
                ? new WasapiLoopbackCapture(captureFromDevice)
                : new WasapiCapture(captureFromDevice);
            _buffer = new BufferedWaveProvider(_capture.WaveFormat)
            {
                BufferLength = 1024 * 256,
                DiscardOnBufferOverflow = true
            };
            _capture.DataAvailable += OnDataAvailable;
            _volume = new VolumeSampleProvider(_buffer.ToSampleProvider()) { Volume = gain };
            _out = new WasapiOut(playToDevice, AudioClientShareMode.Shared, false, 100);
            _out.Init(_volume.ToWaveProvider16());
            _capture.StartRecording();
            _out.Play();
        }
        catch
        {
            // Release any partially-initialized capture/playback objects before surfacing the error.
            Stop();
            throw;
        }
    }

    public void SetGain(float gain)
    {
        if (_volume != null)
        {
            _volume.Volume = gain;
        }
    }

    public void Stop()
    {
        if (_capture != null)
        {
            _capture.DataAvailable -= OnDataAvailable;
            try
            {
                _capture.StopRecording();
            }
            catch
            {
                // ignored
            }

            _capture.Dispose();
            _capture = null;
        }

        if (_out != null)
        {
            try
            {
                _out.Stop();
            }
            catch
            {
                // ignored
            }

            _out.Dispose();
            _out = null;
        }

        _ownedCaptureDevice?.Dispose();
        _ownedCaptureDevice = null;
        _ownedOutputDevice?.Dispose();
        _ownedOutputDevice = null;
        _buffer = null;
        _volume = null;
    }

    public void Dispose()
    {
        Stop();
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        if (e.BytesRecorded == 0 || _buffer == null)
        {
            return;
        }

        _buffer.AddSamples(e.Buffer, 0, e.BytesRecorded);
    }
}
